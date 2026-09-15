package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKSolver
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test

/**
 * Diagnostic that counts how many fixed-reference CCD iterations are required to
 * reach practical positional accuracy thresholds after the normal per-tick solve.
 *
 * The private solve(0) call performs exactly one CCD pass because IKSolver.solve(Int)
 * iterates over 0..iterations. Invoking it directly avoids resetChain(), so the
 * tracked-rotation reference remains fixed while the extra iteration count is measured.
 */
class PositionalIKIterationCountDiagnosticsTests {

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
			name = "iteration-count-${position.name}",
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

		// Consume the normal solve budget once. The following diagnostic adds one
		// private CCD pass at a time without rebuilding FK or resetting references.
		hpm.update()

		return Fixture(hpm, trackers.hip, leftFoot, rightFoot)
	}

	private fun residualMm(fixture: Fixture): Float {
		val hip = (fixture.hpm.skeleton.hipTrackerBone.getTailPosition() - fixture.hip.position).len()
		val left = (fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition() - fixture.leftFoot.position).len()
		val right = (fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition() - fixture.rightFoot.position).len()
		return maxOf(hip, left, right) * 1000f
	}

	private fun runSinglePrivateIteration(solver: IKSolver) {
		val method = solver.javaClass.getDeclaredMethod("solve", Int::class.javaPrimitiveType)
		method.isAccessible = true
		method.invoke(solver, 0)
	}

	private fun runCase(feetHaveRotation: Boolean): String {
		val fixture = createMovedFixture(feetHaveRotation)
		val solver = fixture.hpm.skeleton.ikSolver
		val initialResidual = residualMm(fixture)
		val thresholds = listOf(5f, 2f, 1f, 0.5f, 0.1f)
		val reachedAt = linkedMapOf<Float, Int?>()
		for (threshold in thresholds) {
			reachedAt[threshold] = if (initialResidual <= threshold) 0 else null
		}

		var extraIterations = 0
		while (extraIterations < 400 && reachedAt.values.any { it == null }) {
			runSinglePrivateIteration(solver)
			extraIterations++
			val residual = residualMm(fixture)
			for (threshold in thresholds) {
				if (reachedAt[threshold] == null && residual <= threshold) {
					reachedAt[threshold] = extraIterations
				}
			}
		}

		val thresholdReport = thresholds.joinToString(", ") { threshold ->
			"<=${threshold}mm:${reachedAt[threshold] ?: ">400"} extra"
		}
		return "normal=${IKSolver.MAX_ITERATIONS + 1} passes residual=$initialResidual mm, " +
			"$thresholdReport, final=${residualMm(fixture)} mm"
	}

	@Test
	fun reportIterationsRequiredForAccuracyThresholds(reporter: org.junit.jupiter.api.TestReporter) {
		val sixDof = runCase(feetHaveRotation = true)
		val positionOnly = runCase(feetHaveRotation = false)

		reporter.publishEntry(
			"IK diagnostic",
			"Iteration-count diagnostic. SIX_DOF=[$sixDof]; POSITION_ONLY=[$positionOnly].",
		)
	}
}
