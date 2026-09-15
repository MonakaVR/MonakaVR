package dev.monaka.tracking.desktop

import dev.monaka.protocol.v1.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MonakaDesktopTests {
	@TempDir lateinit var temporary: Path
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "valid/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun tracker(id: Int, body: TrackerPosition, position: Boolean = false) = Tracker(
		null, id, "slime-input:$id", trackerPosition = body, hasPosition = position, hasRotation = true,
		allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
		isHmd = body == TrackerPosition.HEAD,
	).also { it.status = TrackerStatus.OK }
	private fun encoded(value: Envelope) = (MonakaCodec.encodeEnvelope(value) as EncodeResult.Success).value

	@Test fun featurePriorityAndDefaultFalse() {
		assertFalse(MtpFeatureGate.isEnabled(false, null, null))
		assertTrue(MtpFeatureGate.isEnabled(true, "false", "false"))
		assertFalse(MtpFeatureGate.isEnabled(false, "false", "true"))
		assertTrue(MtpFeatureGate.isEnabled(false, null, "true"))
		assertFailsWith<IllegalArgumentException> { MtpFeatureGate.isEnabled(false, "invalid", "true") }
	}

	@Test fun featureOffDoesNotOpenSocketRegisterHookOrChangeSlimeComputedOutput() {
		val left = listOf(tracker(0, TrackerPosition.HEAD, true), tracker(1, TrackerPosition.HIP))
		val right = listOf(tracker(0, TrackerPosition.HEAD, true), tracker(1, TrackerPosition.HIP))
		val expected = HumanPoseManager(left); val actual = HumanPoseManager(right)
		expected.setLegTweaksEnabled(false); actual.setLegTweaksEnabled(false)
		val integration = MonakaServerIntegration.startIfEnabled(
			false, { error("Disabled input read configuration or attempted socket creation") }, { right }, actual.skeleton,
			{ error("Disabled input registered a hook") },
		)
		assertNull(integration)
		repeat(50) { frame ->
			for (list in listOf(left, right)) {
				list[0].position = Vector3(frame * 0.001f, 1.7f, 0f)
				list[1].setRotation(Quaternion(kotlin.math.cos(frame * 0.01f), 0f, kotlin.math.sin(frame * 0.01f), 0f))
			}
			expected.update(); actual.update()
			for ((a, b) in expected.computedTrackers.zip(actual.computedTrackers)) {
				assertEquals(a.position, b.position); assertEquals(a.getRotation(), b.getRotation())
			}
		}
	}

	@Test fun persistentAssignmentsRoundTripAndExplicitMigration() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id)
		val config = MonakaConfiguration(p.coordinate_space)
		config.assignments.migrateLegacy(mapOf("legacy-serial" to TrackerPosition.LEFT_FOOT), mapOf("legacy-serial" to key))
		val path = temporary.resolve("monaka-mtp.json")
		config.save(path)
		val restored = MonakaConfiguration.load(path)
		assertEquals(config.space, restored.space)
		assertEquals(config.assignments.snapshot().entries, restored.assignments.snapshot().entries)
		restored.assignments.assign(key, TrackerPosition.HIP); restored.save(path)
		assertEquals(TrackerPosition.HIP, MonakaConfiguration.load(path).assignments.snapshot().entries[key])
	}

	@Test fun loopbackWorkerSurvivesMalformedInputAndStopsOnUnregister() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id)
		val head = tracker(0, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val hpm = HumanPoseManager(listOf(head)); hpm.setLegTweaksEnabled(false)
		var hook: Runnable? = null
		val failures = mutableListOf<Exception>()
		val integration = MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration(p.coordinate_space, TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP)), port = 0) },
			{ listOf(head) }, hpm.skeleton, { hook = it }, { failures += it },
		)!!
		val bound = integration.receiver.port
		try {
			DatagramSocket().use { sender ->
				for (data in listOf("{".toByteArray(), ByteArray(5000), encoded(p))) {
					sender.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), bound))
				}
			}
			await { hook!!.run(); hpm.update(); integration.runtime.pipeline.observationCount == 2 }
			assertEquals(1, integration.runtime.inbox.diagnostics().getValue("MalformedJson"))
			assertEquals(1, integration.runtime.inbox.diagnostics().getValue("TooLarge"))
			assertTrue(integration.receiver.isAlive); assertTrue(failures.isEmpty())
		} finally { integration.close() }
		assertNull(hook); assertFalse(integration.receiver.isAlive)
		assertEquals(0, integration.runtime.pipeline.observationCount)
		DatagramSocket(bound, InetAddress.getByName("127.0.0.1")).close()
	}

	@Test fun separateProcessUdpEntersSamePipelineAndMovesExistingIk() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id)
		val head = tracker(0, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val hpm = HumanPoseManager(listOf(head)); hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = false; hpm.update()
		val hip = hpm.skeleton.computedHipTracker!!.position
		var hook: Runnable? = null
		val integration = MonakaServerIntegration.startIfEnabled(
			true, { MonakaConfiguration(p.coordinate_space, TrackerBodyAssignments(mapOf(key to TrackerPosition.HIP)), port = 0) },
			{ listOf(head) }, hpm.skeleton, { hook = it }, { throw it },
		)!!
		integration.use {
			val fixtureFile = temporary.resolve("pose.json").toFile()
			fixtureFile.writeBytes(encoded(p.copy(position = listOf(hip.x.toDouble(), hip.y.toDouble(), hip.z.toDouble()))))
			fun publish(offset: Double, sequence: Long) {
				val output = temporary.resolve("publisher-$sequence.log").toFile()
				val process = ProcessBuilder(
					File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
					"-cp", System.getProperty("monaka.test.classpath"), "dev.monaka.tracking.desktop.MtpTestPublisher",
					fixtureFile.absolutePath, integration.receiver.port.toString(), offset.toString(), sequence.toString(),
				).redirectErrorStream(true).redirectOutput(output).start()
				assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Publisher timed out: ${output.readText()}")
				assertEquals(0, process.exitValue(), output.readText())
				await { hook!!.run(); hpm.update(); integration.runtime.mtp.samples()[key]?.pose?.sequence == sequence }
			}
			publish(0.0, 0)
			hpm.skeleton.ikSolver.resetOffsets(); hpm.skeleton.ikSolver.enabled = true; hook!!.run(); hpm.update()
			val baseline = hpm.skeleton.computedHipTracker!!.position
			publish(0.1, 1)
			repeat(5) { hook!!.run(); hpm.update() }
			assertTrue(hpm.skeleton.computedHipTracker!!.position.x - baseline.x > 0.005f)
			assertEquals(setOf("slime", "mtp"), integration.runtime.runner.snapshot().keys)
		}
	}

	private fun await(condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (!condition()) { assertTrue(System.nanoTime() < deadline, "UDP condition timed out"); Thread.sleep(5) }
	}
}

/** Separate JVM producer. Only the fixed C1 codec constructs wire messages. */
object MtpTestPublisher {
	@JvmStatic fun main(args: Array<String>) {
		val p = (MonakaCodec.decodeEnvelope(File(args[0]).readBytes()) as DecodeResult.Success).value as MtpPose
		val pose = p.copy(sequence = args[3].toLong(), position = p.position!!.mapIndexed { i, n -> if (i == 0) n + args[2].toDouble() else n })
		val bytes = (MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value
		DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), args[1].toInt())) }
		println("PASS fixed-codec separate-process UDP sequence=${pose.sequence}")
	}
}
