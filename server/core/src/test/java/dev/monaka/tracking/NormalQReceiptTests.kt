package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NormalQReceiptTests {
	@Test fun normalExternalQUsesActualReceiptWithoutBecomingPhysicalImuEvidence() {
		val q = Tracker(
			null, 700, "external-q", trackerPosition = TrackerPosition.HIP, hasRotation = true,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
		).also {
			it.status = TrackerStatus.OK
			it.setRotation(Quaternion.rotationAroundYAxis(.2f))
		}
		val main = LogicalTracker("source", "main", "publisher")
		val assignments = TrackerBodyAssignments().also { it.configureSources(TrackerPosition.HIP, TrackerReference.slime(q.name), TrackerReference.mtp(main), true) }
		var now = 1_000_000_000L
		MonakaRuntime({ listOf(q) }, CoordinateSpace("normal", "rh_y_up_neg_z_forward", 0), assignments, clock = { now }).use { runtime ->
			var tick = runtime.tickSnapshot()
			val observation = runtime.pipeline.observations(now).single()
			assertNotNull(observation.provenance)
			assertNull(observation.correctionRotation, "Ordinary Q does not acquire independent physical IMU evidence")
			assertEquals("slime:${q.name}", tick.constraints.getValue(TrackerPosition.HIP).rotation!!.sourceId)
			runtime.correctBodyRotations(tick, runtime.resolvedTrackingPoses(tick))
			assertFalse(runtime.bodyRotationResults.getValue(TrackerPosition.HIP).state.ready)
			now += 600_000_000
			tick = runtime.tickSnapshot()
			assertNull(tick.constraints[TrackerPosition.HIP]?.rotation, "Polling does not refresh a local Q receipt")
			q.setRotation(Quaternion.IDENTITY)
			now += 1_000_000
			assertNotNull(runtime.tickSnapshot().constraints.getValue(TrackerPosition.HIP).rotation)
			FeedbackExclusion.registerOwnedOutput(q)
			assertNull(runtime.tickSnapshot().constraints[TrackerPosition.HIP]?.rotation, "Owned output cannot become a normal Q source")
		}
	}
}
