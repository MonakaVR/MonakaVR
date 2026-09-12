package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Characterization tests for multiple simultaneous positional constraints.
 */
class PositionalIKMultiConstraintTests {

	@Test
	fun hipAndBothFeetCanBeConstrainedSimultaneously() {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = trackers.mkTrack(7, TrackerPosition.LEFT_FOOT)
		val rightFoot = trackers.mkTrack(8, TrackerPosition.RIGHT_FOOT)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip, leftFoot, rightFoot))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)
		leftFoot.setRotation(Quaternion.IDENTITY)
		rightFoot.setRotation(Quaternion.IDENTITY)

		// Establish the native FK pose before positional IK participates.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeftFoot = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRightFoot = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		// Calibrate all three external positional trackers against the current
		// skeleton pose. This isolates solver behavior from mounting offsets.
		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeftFoot.position
		rightFoot.position = computedRightFoot.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val baselineHip = computedHip.position
		val baselineLeftFoot = computedLeftFoot.position
		val baselineRightFoot = computedRightFoot.position

		// Drive the two leg branches in opposite directions while retaining the hip
		// as a third positional constraint. The solver must satisfy more than one
		// downstream chain in the same update loop.
		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)
		repeat(10) {
			hpm.update()
		}

		val leftDx = computedLeftFoot.position.x - baselineLeftFoot.x
		val rightDx = computedRightFoot.position.x - baselineRightFoot.x
		val hipMovement = (computedHip.position - baselineHip).len()

		assertTrue(
			leftDx > 0.005f,
			"Left foot did not follow its positional constraint. Expected positive X movement, got $leftDx m.",
		)
		assertTrue(
			rightDx < -0.005f,
			"Right foot did not follow its positional constraint. Expected negative X movement, got $rightDx m.",
		)
		assertTrue(
			computedLeftFoot.position.x.isFinite() &&
				computedLeftFoot.position.y.isFinite() &&
				computedLeftFoot.position.z.isFinite() &&
				computedRightFoot.position.x.isFinite() &&
				computedRightFoot.position.y.isFinite() &&
				computedRightFoot.position.z.isFinite() &&
				computedHip.position.x.isFinite() &&
				computedHip.position.y.isFinite() &&
				computedHip.position.z.isFinite(),
			"Multi-constraint solve produced a non-finite computed tracker pose.",
		)
		assertTrue(
			hipMovement < 0.10f,
			"Hip moved excessively while solving opposing foot constraints: $hipMovement m.",
		)
	}
}
