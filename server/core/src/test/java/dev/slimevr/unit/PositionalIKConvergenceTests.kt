package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Characterization tests for the convergence and residual error of the existing
 * positional CCDIK path when several 6DoF constraints are active at once.
 */
class PositionalIKConvergenceTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val hipTarget: Vector3,
		val leftTarget: Vector3,
		val rightTarget: Vector3,
	)

	private fun copyOf(v: Vector3): Vector3 = Vector3(v.x, v.y, v.z)

	private fun createMovedThreePointFixture(): Fixture {
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

		// Establish the unconstrained FK pose, then align the physical positional
		// trackers with the current computed tracker positions before calibration.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeftFoot = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRightFoot = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeftFoot.position
		rightFoot.position = computedRightFoot.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		// Move the physical trackers after calibration. The IK targets are the raw
		// tracker positions themselves in this zero-mounting-offset fixture. Using
		// the computed output as the target would incorrectly include any solver
		// movement that happened during calibration.
		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return Fixture(
			hpm = hpm,
			hipTarget = copyOf(trackers.hip.position),
			leftTarget = copyOf(leftFoot.position),
			rightTarget = copyOf(rightFoot.position),
		)
	}

	private fun maxResidual(fixture: Fixture): Float {
		val hip = fixture.hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val left = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val right = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		val hipError = (hip.position - fixture.hipTarget).len()
		val leftError = (left.position - fixture.leftTarget).len()
		val rightError = (right.position - fixture.rightTarget).len()
		return maxOf(hipError, leftError, rightError)
	}

	@Test
	fun oneTickConvergesNearMultiConstraintSteadyState() {
		val fixture = createMovedThreePointFixture()

		// One update contains one IKSolver.solve() call, i.e. the current
		// MAX_ITERATIONS budget. Compare it with the residual after another nine
		// complete pose ticks to determine whether one solve is effectively settled.
		fixture.hpm.update()
		val oneTickResidual = maxResidual(fixture)

		repeat(9) {
			fixture.hpm.update()
		}
		val steadyResidual = maxResidual(fixture)
		val additionalImprovement = oneTickResidual - steadyResidual

		assertTrue(
			abs(additionalImprovement) < 0.002f,
			"One positional IK tick was not near steady state. " +
				"Residual after one tick=${oneTickResidual * 1000f} mm, " +
				"after ten ticks=${steadyResidual * 1000f} mm, " +
				"difference=${additionalImprovement * 1000f} mm.",
		)
	}

	@Test
	fun multiConstraintSteadyStateResidualStaysWithinOneMillimeter() {
		val fixture = createMovedThreePointFixture()

		repeat(10) {
			fixture.hpm.update()
		}

		val residual = maxResidual(fixture)
		assertTrue(
			residual < 0.001f,
			"Three-point positional IK steady-state residual exceeded 1 mm: " +
				"${residual * 1000f} mm.",
		)
	}
}
