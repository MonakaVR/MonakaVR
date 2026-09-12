package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for the higher-gain positional IK tuning. Alternating targets
 * exercise repeated large direction reversals so reduced damping/annealing cannot
 * silently introduce divergence or one-frame overshoot beyond the intended budget.
 */
class PositionalIKDynamicStabilityTests {

	private fun copyOf(v: Vector3): Vector3 = Vector3(v.x, v.y, v.z)

	@Test
	fun alternatingSixDofTargetsStayFiniteAndSubTwoMillimeters() {
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

		// Establish FK baseline and calibrate zero positional mounting offset.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeft = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		trackers.hip.position = copyOf(computedHip.position)
		leftFoot.position = copyOf(computedLeft.position)
		rightFoot.position = copyOf(computedRight.position)
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val hipTarget = copyOf(trackers.hip.position)
		val leftBase = copyOf(leftFoot.position)
		val rightBase = copyOf(rightFoot.position)
		var worstResidual = 0f

		// Flip between +/-50 mm each frame. After the first frame this is a 100 mm
		// target reversal every tick, which is intentionally harsher than normal motion.
		repeat(20) { frame ->
			val direction = if (frame % 2 == 0) 1f else -1f
			leftFoot.position = leftBase + Vector3(0.05f * direction, 0f, 0f)
			rightFoot.position = rightBase + Vector3(-0.05f * direction, 0f, 0f)

			hpm.update()

			val hip = hpm.skeleton.computedHipTracker
				?: error("Computed hip tracker was not initialized")
			val left = hpm.skeleton.computedLeftFootTracker
				?: error("Computed left foot tracker was not initialized")
			val right = hpm.skeleton.computedRightFootTracker
				?: error("Computed right foot tracker was not initialized")

			val residual = maxOf(
				(hip.position - hipTarget).len(),
				(left.position - leftFoot.position).len(),
				(right.position - rightFoot.position).len(),
			)

			assertTrue(residual.isFinite(), "Positional IK produced a non-finite residual on frame $frame")
			worstResidual = maxOf(worstResidual, residual)
		}

		assertTrue(
			worstResidual < 0.002f,
			"Alternating 6DoF targets exceeded 2 mm one-frame residual: ${worstResidual * 1000f} mm.",
		)
	}
}
