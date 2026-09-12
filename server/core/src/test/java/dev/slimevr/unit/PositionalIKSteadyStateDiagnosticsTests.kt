package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKChain
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 * Diagnostic for the residual floor that remains after the stale IK rotation-cache
 * fix. It compares 6DoF feet against position-only feet and reports both output-space
 * residuals and the solver's own chain residuals after one and ten pose ticks.
 */
class PositionalIKSteadyStateDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val hip: Tracker,
		val leftFoot: Tracker,
		val rightFoot: Tracker,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "steady-state-${position.name}",
			trackerPosition = position,
			trackerNum = 0,
			hasPosition = true,
			hasRotation = hasRotation,
			isComputed = false,
			allowReset = hasRotation,
			allowMounting = hasRotation,
			trackRotDirection = false,
		)
		tracker.status = TrackerStatus.OK
		if (hasRotation) tracker.setRotation(Quaternion.IDENTITY)
		return tracker
	}

	private fun createMovedFixture(feetHaveRotation: Boolean): Fixture {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = makeFootTracker(7, TrackerPosition.LEFT_FOOT, feetHaveRotation)
		val rightFoot = makeFootTracker(8, TrackerPosition.RIGHT_FOOT, feetHaveRotation)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip, leftFoot, rightFoot))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Establish the FK pose with positional IK disabled, then make the three
		// positional constraints coincide exactly with the current outputs.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeft = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeft.position
		rightFoot.position = computedRight.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return Fixture(hpm, trackers.hip, leftFoot, rightFoot)
	}

	@Suppress("UNCHECKED_CAST")
	private fun constrainedChains(hpm: HumanPoseManager): List<IKChain> {
		val solver = hpm.skeleton.ikSolver
		val field = solver.javaClass.getDeclaredField("chainList")
		field.isAccessible = true
		return (field.get(solver) as List<IKChain>).filter { it.tailConstraint != null }
	}

	private fun outputResiduals(fixture: Fixture): String {
		val computedHip = fixture.hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeft = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		val hip = (computedHip.position - fixture.hip.position).len() * 1000f
		val left = (computedLeft.position - fixture.leftFoot.position).len() * 1000f
		val right = (computedRight.position - fixture.rightFoot.position).len() * 1000f
		return "output=[hip=$hip mm, left=$left mm, right=$right mm, max=${maxOf(hip, left, right)} mm]"
	}

	private fun solverResiduals(fixture: Fixture): String {
		return constrainedChains(fixture.hpm)
			.joinToString(", ") { chain ->
				val tracker = chain.tailConstraint ?: error("Expected positional tail constraint")
				val residualMm = sqrt(chain.distToTargetSqr) * 1000f
				"${tracker.trackerPosition}:$residualMm mm"
			}
	}

	private fun runCase(feetHaveRotation: Boolean): String {
		val fixture = createMovedFixture(feetHaveRotation)
		val snapshots = mutableListOf<String>()

		repeat(10) { index ->
			fixture.hpm.update()
			val tick = index + 1
			if (tick == 1 || tick == 10) {
				snapshots += "tick=$tick ${outputResiduals(fixture)} solver=[${solverResiduals(fixture)}]"
			}
		}

		return snapshots.joinToString(" | ")
	}

	@Test
	fun reportSteadyStateResidualFloorByConstraintType() {
		val sixDof = runCase(feetHaveRotation = true)
		val positionOnly = runCase(feetHaveRotation = false)

		assertTrue(
			false,
			"Steady-state residual diagnostic. SIX_DOF=[$sixDof]; POSITION_ONLY=[$positionOnly].",
		)
	}
}
