package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Output-owner objects, not string classification, are the test oracle. */
class OwnedFeedbackBoundaryTests {
	private val space = CoordinateSpace("world", "rh_y_up_neg_z_forward", 1)

	@Test fun actualDirectOutputCannotEnterTheRawAdapter() {
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime("physical"), outputMode = OutputMode.DIRECT)
		}
		DirectConstraintOutput(assignments.snapshot(), space) { 42 }.use { output ->
			val owned = output.trackers.getValue(TrackerPosition.HIP)
			assertNull(SlimeTrackerPoseObservationAdapter().adapt(owned, 100))
			assertTrue(SlimeTrackerObservationBackend("raw", "raw", { listOf(owned) }).poll(100).isEmpty())
		}
	}

	@Test fun actualComputedHipCannotEnterTheRawAdapter() {
		val manager = HumanPoseManager(emptyList())
		val owned = manager.skeleton.computedHipTracker!!
		assertNull(SlimeTrackerPoseObservationAdapter().adapt(owned, 100))
		val capture = SlimeIndependentImuOrientationCapture { error("Rejected output must not sample the clock") }
		assertEquals(
			SlimeImuCaptureRejection.OUTPUT_SOURCE_NOT_ALLOWED,
			assertIs<SlimeImuCaptureResult.Unavailable>(capture.capture(owned, 100)).reason,
		)
	}

	@Test fun exactOwnershipRejectsArbitraryNamesWithoutClassifyingAnotherObjectWithTheSameIds() {
		fun tracker(name: String, id: Int = 7) = Tracker(
			null, id, name, trackerPosition = TrackerPosition.HIP,
			hasPosition = true, hasRotation = true, trackRotDirection = false,
		).also { it.status = TrackerStatus.OK }
		for (name in listOf("opaque", "unrelated:tracker", "日本語", "reused-id")) {
			val owned = tracker(name)
			val external = tracker(name)
			assertTrue(FeedbackExclusion.accepts(owned))
			FeedbackExclusion.registerOwnedOutput(owned)
			FeedbackExclusion.registerOwnedOutput(owned)
			assertFalse(FeedbackExclusion.accepts(owned))
			assertNull(SlimeTrackerPoseObservationAdapter().adapt(owned, 100))
			assertTrue(FeedbackExclusion.accepts(external))
			assertNotNull(SlimeTrackerPoseObservationAdapter().adapt(external, 100))
			assertFalse(FeedbackExclusion.isOwnedOutput(tracker(name, 8)))
		}
	}

	@Test fun actualPrivateProxyRetainsOwnershipAfterItsOwnerCloses() {
		val manager = HumanPoseManager(emptyList())
		val assignments = TrackerBodyAssignments().also {
			it.configure(TrackerPosition.HIP, TrackerReference.slime("external"))
		}
		val writeback = ConstraintIkWriteback(manager.skeleton)
		val constraint = EffectiveConstraint(
			TrackerPosition.HIP,
			ResolvedComponent(Vector3(0f, 1f, 0f), "slime:external", ObservationQuality.TRACKED, 100),
			ResolvedComponent(Quaternion.IDENTITY, "slime:external", ObservationQuality.TRACKED, 100),
		)
		writeback.apply(mapOf(TrackerPosition.HIP to constraint), assignments.snapshot())
		val proxy = assertNotNull(manager.skeleton.hipTracker)
		assertTrue(FeedbackExclusion.isOwnedOutput(proxy))
		assertFalse(proxy.isInternal, "Existing IK requires non-internal constraint inputs")
		assertNull(SlimeTrackerPoseObservationAdapter().adapt(proxy, 100))
		writeback.close()
		assertTrue(FeedbackExclusion.isOwnedOutput(proxy), "Retained old objects must remain excluded")
		assertEquals(
			SlimeImuCaptureRejection.OUTPUT_SOURCE_NOT_ALLOWED,
			assertIs<SlimeImuCaptureResult.Unavailable>(SlimeIndependentImuOrientationCapture { 0 }.capture(proxy, 100)).reason,
		)
	}

	@Test fun externalComputedHmdIsStillAnInputAndNeverRegisteredByName() {
		val hmd = Tracker(
			null, 1, "external-hmd", trackerPosition = TrackerPosition.HEAD,
			hasPosition = true, hasRotation = true, isComputed = true, isHmd = true,
		).also { it.status = TrackerStatus.OK }
		assertFalse(FeedbackExclusion.isOwnedOutput(hmd))
		assertNotNull(SlimeTrackerPoseObservationAdapter().adapt(hmd, 100))
	}
}
