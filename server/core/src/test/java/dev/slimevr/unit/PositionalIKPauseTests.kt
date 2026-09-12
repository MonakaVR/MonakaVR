package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PositionalIKPauseTests {

	@Test
	fun positionalConstraintDoesNotMoveSkeletonWhileTrackingPaused() {
		val trackers = TestTrackerSet(positional = true)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Build the native pose first and initialize the positional mounting offset.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		trackers.hip.position = computedHip.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val baseline = computedHip.position

		// Positional trackers may continue updating while tracking is paused. The
		// skeleton must not consume those position changes until tracking resumes.
		hpm.setPauseTracking(true, "Unit Test")
		trackers.hip.position += Vector3(0.10f, 0f, 0f)
		repeat(5) {
			hpm.update()
		}

		val pausedDisplacement = (computedHip.position - baseline).len()
		assertTrue(
			pausedDisplacement < 0.001f,
			"Positional IK moved the computed hip by $pausedDisplacement m while tracking was paused.",
		)

		// Once tracking resumes, the same still-current positional observation must
		// become effective again without requiring another offset reset.
		hpm.setPauseTracking(false, "Unit Test")
		repeat(5) {
			hpm.update()
		}

		val resumedDisplacementX = computedHip.position.x - baseline.x
		assertTrue(
			resumedDisplacementX > 0.005f,
			"Positional IK did not resume after tracking was unpaused. " +
				"Expected positive X displacement, got $resumedDisplacementX m.",
		)
	}
}
