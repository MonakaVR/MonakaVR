package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.*
import dev.monaka.tracking.*
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.*

class HybridServerIntegrationTests {
	private fun fixture() = (MonakaCodec.decodeEnvelope(File(System.getProperty("monaka.fixtures"), "v2/mtp-pose.json").readBytes()) as DecodeResult.Success).value as MtpPose
	private fun tracker(id: Int, body: TrackerPosition, position: Boolean) = Tracker(null, id, "test-input:$id",
		trackerPosition = body, hasPosition = position, hasRotation = true, isHmd = body == TrackerPosition.HEAD,
		allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false).also { it.status = TrackerStatus.OK }

	@Test fun productionHooksComposeSameTickBackgroundOnlyAfterExistingIkAndClearOnClose() {
		val p = fixture(); val key = LogicalTracker(p.source_id, p.tracker_id, p.publisher_id)
		val head = tracker(1, TrackerPosition.HEAD, true).also { it.position = Vector3(0f, 1.7f, 0f) }
		val imu = tracker(2, TrackerPosition.HIP, false)
		val hpm = HumanPoseManager(listOf(head, imu)); hpm.setLegTweaksEnabled(false)
		hpm.skeleton.ikSolver.enabled = true; hpm.update()
		val a = TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.mtp(key), TrackerReference.slime(imu.name), OutputMode.HYBRID) }
		var before: Runnable? = null; var after: Runnable? = null
		var now = 1_000_000_000L
		val events = mutableListOf<OutputTransition>(); val failures = mutableListOf<Exception>()
		val outputs = mutableListOf<Tracker>()
		val integration = MonakaServerIntegration.startIfEnabled(true,
			{ MonakaConfiguration(p.coordinate_space, a, port = 0, backgroundIkSharedSpace = p.coordinate_space) },
			{ listOf(head, imu) }, hpm.skeleton, { before = it }, { failures += it }, { now },
			configureDirectOutputs = { outputs += it }, nextTrackerId = { 100 }, registerAfterPose = { after = it }, onTransition = events::add,
		)!!
		integration.use {
			val output = outputs.single(); val identity = Triple(output.id, output.name, output.trackerPosition)
			for ((sequence, mode) in listOf("full", "full", "rotation_only", "rotation_only", "full").withIndex()) {
				now += 10_000_000
				val pose = p.copy(sequence = sequence.toLong(), modality = mode,
					position = if (mode == "full") listOf(.1, 1.0, .1) else null,
					validity = Validity(mode == "full", true), confidence = Confidence(if (mode == "full") 1.0 else 0.0, 1.0),
					tracking_state = if (mode == "full") "tracked" else "degraded")
				assertTrue(integration.runtime.inbox.receive((MonakaCodec.encodeEnvelope(pose) as EncodeResult.Success).value, now))
				before!!.run()
				assertNull(output.monakaOutputPose!!.position) // no stale previous-tick output before solve
				hpm.update()
				after!!.run()
				assertEquals(identity, Triple(output.id, output.name, output.trackerPosition))
				assertNotNull(hpm.skeleton.hipTracker) // participates even while output is Direct
				if (sequence < 2) {
					assertEquals(Vector3(.1f, 1f, .1f), output.monakaOutputPose!!.position!!.value)
					assertEquals(OutputPositionSource.RESOLVED_MAIN, output.monakaOutputPose!!.positionSource)
				} else {
					assertEquals(hpm.skeleton.computedHipTracker!!.position, output.monakaOutputPose!!.position!!.value)
					assertEquals(OutputPositionSource.BACKGROUND_IK, output.monakaOutputPose!!.positionSource)
					assertEquals(if (mode == "full") key.observationId else "slime:${imu.name}", output.monakaOutputPose!!.rotationOwner)
				}
				val eventCount = events.size; after!!.run(); assertEquals(eventCount, events.size) // frame consumed once
			}
			assertEquals(listOf(ContinuityState.MAIN_DIRECT, ContinuityState.FALLBACK_ACTIVE, ContinuityState.REACQUIRING), events.map { it.state })
			assertTrue(failures.isEmpty())
		}
		assertNull(before); assertNull(after); assertFalse(integration.receiver.isAlive)
		assertFalse(outputs.single().monakaOutputPose!!.positionValid)
	}

	@Test fun hybridRequiresPostIkHookAndUnknownAlignmentCannotManufactureFallback() {
		val p = fixture(); val a = TrackerBodyAssignments().also { it.configure(TrackerPosition.HIP, TrackerReference.slime("main"), outputMode = OutputMode.HYBRID) }
		val hpm = HumanPoseManager(emptyList())
		assertFailsWith<IllegalArgumentException> {
			MonakaServerIntegration.startIfEnabled(true, { MonakaConfiguration(p.coordinate_space, a, port = 0) }, { emptyList() }, hpm.skeleton, {})
		}
		val reader = BackgroundIkPoseReader(hpm.skeleton, BackgroundIkAlignment.UNVERIFIED)
		assertNull(reader.read(TrackerPosition.HIP, p.coordinate_space, 0).pose)
	}
}
