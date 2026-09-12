package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKSolver
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Diagnostic that distinguishes a true per-solve iteration-budget limit from
 * apparent convergence caused by repeatedly calling the public solve() wrapper.
 *
 * The public wrapper calls resetChain(), which refreshes each tracker-backed
 * rotation constraint's initialRotation. Repeating public solve() can therefore
 * move the reference frame between calls. Here we invoke the private iterative
 * solve(Int) method directly after one normal pose tick so the original tracked
 * rotation reference remains fixed while additional CCD iterations run.
 */
class PositionalIKFixedReferenceIterationDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftFoot: Tracker,
		val rightFoot: Tracker,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "fixed-reference-${position.name}",
			trackerPosition = position,
			trackerNum = 0,
			hasPosition = true,
			hasRotation = true,
			isComputed = false,
			allowReset = true,
			allowMounting = true,
			trackRotDirection = false,
		)
		tracker.status = TrackerStatus.OK
		tracker.setRotation(Quaternion.IDENTITY)
		return tracker
	}

	private fun createMovedFixture(): Fixture {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = makeFootTracker(7, TrackerPosition.LEFT_FOOT)
		val rightFoot = makeFootTracker(8, TrackerPosition.RIGHT_FOOT)
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

		// One ordinary pose tick establishes the tracked-rotation reference and
		// consumes the normal MAX_ITERATIONS budget.
		hpm.update()
		return Fixture(hpm, leftFoot, rightFoot)
	}

	private fun residualMm(fixture: Fixture): Float {
		val left = (fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition() - fixture.leftFoot.position).len()
		val right = (fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition() - fixture.rightFoot.position).len()
		return maxOf(left, right) * 1000f
	}

	private fun rotationDeviationDeg(fixture: Fixture): Float {
		val leftBone = fixture.hpm.skeleton.leftFootBone
		val rightBone = fixture.hpm.skeleton.rightFootBone
		val leftInitial = leftBone.rotationConstraint.initialRotation
		val rightInitial = rightBone.rotationConstraint.initialRotation
		val leftError = (leftBone.getGlobalRotation() * leftInitial.inv()).angleR() * FastMath.RAD_TO_DEG
		val rightError = (rightBone.getGlobalRotation() * rightInitial.inv()).angleR() * FastMath.RAD_TO_DEG
		return maxOf(leftError, rightError)
	}

	private fun runPrivateIterations(solver: IKSolver, iterations: Int) {
		val method = solver.javaClass.getDeclaredMethod("solve", Int::class.javaPrimitiveType)
		method.isAccessible = true
		method.invoke(solver, iterations)
	}

	@Test
	fun reportAdditionalIterationsWithFixedTrackedRotationReference() {
		val fixture = createMovedFixture()
		val solver = fixture.hpm.skeleton.ikSolver
		val trace = mutableListOf<String>()

		trace += "normalBudget residual=${residualMm(fixture)} mm rotationDeviation=${rotationDeviationDeg(fixture)} deg"

		repeat(10) { index ->
			runPrivateIterations(solver, IKSolver.MAX_ITERATIONS)
			fixture.hpm.skeleton.headBone.update()
			val call = index + 1
			if (call == 1 || call == 2 || call == 5 || call == 10) {
				trace += "fixedRefExtraCall=$call residual=${residualMm(fixture)} mm " +
					"rotationDeviation=${rotationDeviationDeg(fixture)} deg"
			}
		}

		assertTrue(
			false,
			"Fixed-reference iteration diagnostic. ${trace.joinToString(" | ")}",
		)
	}
}
