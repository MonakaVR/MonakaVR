package dev.monaka.tracking.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import dev.monaka.protocol.v3.DecodeResult
import dev.monaka.protocol.v3.EncodeResult
import dev.monaka.protocol.v3.Envelope
import dev.monaka.protocol.v3.MonakaCodec
import dev.monaka.protocol.v3.MtpPose
import dev.monaka.protocol.v3.MtpTrackerState
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.MonakaConfiguration
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Vector3
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class C3MtpDesktopTests {
	private fun readFixture(name: String): Envelope {
		val path = Path.of(System.getProperty("monaka.c3Fixtures")).resolve(name)
		return (MonakaCodec.decodeEnvelope(Files.readAllBytes(path)) as DecodeResult.Success).value
	}

	@ParameterizedTest
	@ValueSource(strings = ["synthetic-bridge", "opaque:other-publisher"])
	fun udpAssignmentSolverAndSharedWatermark(publisher: String, @TempDir directory: Path) {
		val fixture = (readFixture("mtp-pose.json") as MtpPose).copy(publisher_id = publisher)
		val state = (readFixture("tracker-state.json") as MtpTrackerState).copy(publisher_id = publisher)
		val key = LogicalTracker(fixture.source_id, fixture.tracker_id, publisher)
		val path = directory.resolve("monaka-mtp.json")
		Files.writeString(path, """{"version":5,"convention":"rh_y_up_neg_z_forward","port":0,"assignments":[]}""")
		val time = AtomicLong(1_000_000_000)
		val head = Tracker(
			null, 1, "local-head", trackerPosition = TrackerPosition.HEAD, hasPosition = true, hasRotation = true,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false, isHmd = true,
		).also {
			it.status = TrackerStatus.OK
			it.position = Vector3(0f, 1.7f, 0f)
		}
		val poseManager = HumanPoseManager(listOf(head)).also {
			it.setLegTweaksEnabled(false)
			it.skeleton.ikSolver.enabled = false
			it.update()
		}
		var before: Runnable? = null
		var after: Runnable? = null
		val integration = requireNotNull(MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration.load(path) }, { listOf(head) }, poseManager.skeleton,
			{ before = it }, { throw it }, time::get, registerAfterPose = { after = it }, configurationPath = path,
		))
		integration.use {
			DatagramSocket(0, InetAddress.getLoopbackAddress()).use { sender ->
				fun tick() {
					requireNotNull(before).run()
					poseManager.update()
					requireNotNull(after).run()
				}
				fun send(envelope: Envelope, elapsed: Long = 10_000_000) {
					time.addAndGet(elapsed)
					val validated = it.runtime.inbox.diagnostics()["Validated"] ?: 0
					val bytes = (MonakaCodec.encodeEnvelope(envelope) as EncodeResult.Success).value
					sender.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), it.receiver.port))
					val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
					while ((it.runtime.inbox.diagnostics()["Validated"] ?: 0) == validated) {
						assertTrue(System.nanoTime() < deadline, "UDP validation timed out: ${it.runtime.inbox.diagnostics()}")
						Thread.sleep(2)
					}
					tick()
				}
				fun sample() = it.runtime.mtp.sampleFor(key)
				fun constraint() = it.runtime.pipeline.resolve(TrackerPosition.HIP, time.get())

				val hip = poseManager.skeleton.computedHipTracker!!.position
				val first = fixture.copy(sequence = 10, position = listOf(hip.x.toDouble(), hip.y.toDouble(), hip.z.toDouble()))
				send(first)
				val admitted = assertNotNull(sample())
				assertEquals(time.get() - 100, admitted.sampleTime)
				assertNull(admitted.legacyPose)
				val mapper = ObjectMapper()
				fun rpc(action: String, fields: Map<String, Any?> = emptyMap()) = mapper.readTree(it.inputRequest(mapper.writeValueAsString(
					mapOf("type" to "monaka_inputs_v1", "id" to "c3-ingress", "action" to action) + fields,
				)))
				val generation = rpc("snapshot")["generation"].asText()
				val saved = rpc("save", mapOf("generation" to generation, "body" to "HIP", "qSource" to null,
					"sixDofSource" to key.observationId, "manualSix" to true))
				assertTrue(saved["ok"].asBoolean(), saved.toString())
				val stored = mapper.readTree(path.toFile())
				assertEquals(5, stored["version"].asInt())
				assertEquals(first.convention, stored["convention"].asText())
				assertFalse(stored.has("space"))
				assertEquals(key, MonakaConfiguration.load(path).assignments.snapshot().targets.getValue(TrackerPosition.HIP).mainTracker.mtp)
				tick()
				assertEquals(key.observationId, constraint().position?.sourceId)
				val provenance = assertNotNull(it.runtime.pipeline.observations().single { row -> row.sourceId == key.observationId }.provenance)
				assertEquals(first.convention, provenance.coordinateConvention)
				assertNull(provenance.space)
				assertNull(provenance.mappingRevision)
				poseManager.skeleton.ikSolver.resetOffsets()
				poseManager.skeleton.ikSolver.enabled = true
				tick()
				val baselineX = poseManager.skeleton.computedHipTracker!!.position.x

				send(state.copy(sequence = 11))
				assertSame(admitted, sample())
				send(first.copy(sequence = 11, input = first.input.copy(sequence = 99)))
				assertSame(admitted, sample())
				val reexpressed = first.copy(
					sequence = 12,
					position = listOf(hip.x.toDouble() + 0.15, hip.y.toDouble(), hip.z.toDouble()),
					sent_at_ns = first.sent_at_ns + 20_000_000,
				)
				send(reexpressed)
				val updated = assertNotNull(sample())
				assertEquals(reexpressed.sequence, updated.pose.sequence)
				assertEquals(first.sequence, updated.acquisitionSequence)
				assertEquals(admitted.sampleTime, updated.sampleTime)
				assertEquals(admitted.receivedAtNanos, updated.receivedAtNanos)
				repeat(5) { tick() }
				assertTrue(poseManager.skeleton.computedHipTracker!!.position.x - baselineX > 0.005f)

				val lost = state.copy(sequence = 13, presence = "absent", tracking_state = "lost", timestamp_ns = first.timestamp_ns + 1)
				send(lost.copy(sequence = 11))
				assertSame(updated, sample())
				send(lost)
				assertNull(sample())
				assertNull(constraint().position)
				send(reexpressed.copy(sequence = 14))
				assertNull(sample())
				val fresh = reexpressed.copy(sequence = 15, timestamp_ns = lost.timestamp_ns + 1, input = first.input.copy(sequence = 1))
				send(fresh)
				val resumed = assertNotNull(sample())
				assertTrue(resumed.sampleTime > updated.sampleTime)
				assertNotNull(constraint().position)
				send(fresh.copy(sequence = 16, sent_at_ns = fresh.sent_at_ns + 20_000_000), elapsed = 550_000_000)
				assertEquals(resumed.sampleTime, assertNotNull(sample()).sampleTime)
				assertNull(constraint().position)

				poseManager.skeleton.setPauseTracking(true, "c3 replay test")
				val pausedInput = fresh.copy(sequence = 17, timestamp_ns = fresh.timestamp_ns + 1, input = first.input.copy(sequence = 2))
				send(pausedInput)
				assertNull(sample())
				poseManager.skeleton.setPauseTracking(false, "c3 replay test")
				tick()
				send(fresh.copy(sequence = 18))
				assertNull(sample())
				send(pausedInput.copy(sequence = 19))
				assertNull(sample())
				send(pausedInput.copy(sequence = 20, timestamp_ns = pausedInput.timestamp_ns + 1, input = first.input.copy(sequence = 3)))
				assertNotNull(constraint().position)
				assertEquals(2, it.runtime.inbox.diagnostics()["DuplicateOrOldSequence"])
				assertEquals(1, it.runtime.inbox.diagnostics()["OldAcquisition"])
				assertEquals(1, it.runtime.inbox.diagnostics()["ReexpressionWithoutAcquisition"])
			}
		}
	}
}
