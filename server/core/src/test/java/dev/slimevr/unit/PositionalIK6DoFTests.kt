package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.tracking.processor.HumanPoseManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Characterization tests for simultaneous positional and rotational 6DoF
 * constraints using SlimeVR's existing positional IK solver.
 */
class PositionalIK6DoFTests {

	@Test
	fun simultaneousPositionAndRotationConstraintPreservesTrackedRotation() {
		val trackers = TestTrackerSet(positional = true)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Establish a native FK pose and calibrate the positional mounting offset at
		// a zero-error baseline.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		trackers.hip.position = computedHip.position

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		// Apply a translation and a non-trivial rotation together. First run one FK
		// update with IK disabled to capture the rotation that the tracker would
		// normally impose on the hip before CCDIK makes positional corrections.
		hpm.skeleton.ikSolver.enabled = false
		trackers.hip.position += Vector3(0.10f, 0.04f, -0.03f)
		trackers.hip.setRotation(Quaternion(0.9238795f, 0f, 0.38268343f, 0f))
		hpm.update()

		val fkOnlyHipPosition = computedHip.position
		val trackedHipRotation = hpm.skeleton.hipBone.getGlobalRotation()

		// Re-enable IK without resetting offsets. The old positional calibration is
		// intentionally retained so the translated tracker remains a live target.
		hpm.skeleton.ikSolver.enabled = true
		repeat(5) {
			hpm.update()
		}

		val displacementX = computedHip.position.x - fkOnlyHipPosition.x
		assertTrue(
			displacementX > 0.005f,
			"The simultaneous 6DoF constraint did not produce positional correction. " +
				"Expected positive X displacement, got $displacementX m.",
		)

		// Tracker-backed rotations are allowed to deviate by at most 15 degrees in
		// Constraint.constrainToInitialRotation(). Use a small epsilon around that
		// explicit solver limit to detect CCDIK destroying the tracked orientation.
		val solvedHipRotation = hpm.skeleton.hipBone.getGlobalRotation()
		val rotationErrorRad = (solvedHipRotation * trackedHipRotation.inv()).angleR()
		val maxAllowedErrorRad = 15.5f * FastMath.DEG_TO_RAD
		assertTrue(
			rotationErrorRad <= maxAllowedErrorRad,
			"Positional CCDIK overrode the tracked hip rotation by " +
				"${rotationErrorRad * FastMath.RAD_TO_DEG} degrees; expected <= 15.5 degrees.",
		)
	}
}
