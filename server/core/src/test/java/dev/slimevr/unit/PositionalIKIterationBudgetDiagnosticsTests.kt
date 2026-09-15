package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test

/**
 * Diagnostic for the residual floor that remains after the IK rotation-cache fix.
 *
 * A full HumanPoseManager update rebuilds the FK input pose before invoking the
 * positional solver. Repeating full ticks therefore does not distinguish a true
 * geometric/constraint floor from an insufficient per-solve iteration budget.
 * This test performs additional IKSolver.solve() calls without rebuilding FK so
 * each solve starts from the previous solved pose.
 */
class PositionalIKIterationBudgetDiagnosticsTests {

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
			name = "iteration-budget-${position.name}",
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

		// One ordinary full pose tick: this is the production per-frame solve budget.
		hpm.update()

		return Fixture(hpm, trackers.hip, leftFoot, rightFoot)
	}

	private fun residualsMm(fixture: Fixture): String {
		val computedHip = fixture.hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeft = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		val hip = (computedHip.position - fixture.hip.position).len() * 1000f
		val left = (computedLeft.position - fixture.leftFoot.position).len() * 1000f
		val right = (computedRight.position - fixture.rightFoot.position).len() * 1000f
		return "hip=$hip mm, left=$left mm, right=$right mm, max=${maxOf(hip, left, right)} mm"
	}

	private fun runCase(feetHaveRotation: Boolean): String {
		val fixture = createMovedFixture(feetHaveRotation)
		val snapshots = mutableListOf<String>()
		snapshots += "fullTick=${residualsMm(fixture)}"

		for (call in 1..10) {
			fixture.hpm.skeleton.ikSolver.solve()
			// solve() updates bones but does not refresh the computed tracker objects.
			// Compare the actual tracker-bone tails directly by refreshing one ordinary
			// output tick would rebuild FK, so instead use the bone positions here.
			val left = (fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition() - fixture.leftFoot.position).len() * 1000f
			val right = (fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition() - fixture.rightFoot.position).len() * 1000f
			val hip = (fixture.hpm.skeleton.hipTrackerBone.getTailPosition() - fixture.hip.position).len() * 1000f
			if (call == 1 || call == 2 || call == 5 || call == 10) {
				snapshots += "solverCall=$call hip=$hip mm, left=$left mm, right=$right mm, max=${maxOf(hip, left, right)} mm"
			}
		}

		return snapshots.joinToString(" | ")
	}

	@Test
	fun reportResidualAfterAdditionalSolverBudgets(reporter: org.junit.jupiter.api.TestReporter) {
		val sixDof = runCase(feetHaveRotation = true)
		val positionOnly = runCase(feetHaveRotation = false)

		reporter.publishEntry(
			"IK diagnostic",
			"Iteration budget diagnostic. SIX_DOF=[$sixDof]; POSITION_ONLY=[$positionOnly].",
		)
	}
}
