package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Diagnostic that isolates the per-iteration correction toward a tracker-backed
 * bone's initial rotation from the separate 15-degree tracker rotation limit.
 *
 * Setting allowModifications=true skips the CORRECTION_FACTOR interpolation in
 * IKChain.backwardsCCDIK(), while hasTrackerRotation remains true, so
 * constrainToInitialRotation() and the tracked-bone offset path are unchanged.
 */
class PositionalIKTrackerCorrectionDiagnosticsTests {

	private data class Result(
		val residualMm: Float,
		val rotationDeviationDeg: Float,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "tracker-correction-${position.name}",
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

	private fun runCase(skipCorrectionTowardInitial: Boolean): Result {
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

		if (skipCorrectionTowardInitial) {
			hpm.skeleton.leftFootBone.rotationConstraint.allowModifications = true
			hpm.skeleton.rightFootBone.rotationConstraint.allowModifications = true
		}

		hpm.update()

		val leftResidual = (hpm.skeleton.leftFootTrackerBone.getTailPosition() - leftFoot.position).len()
		val rightResidual = (hpm.skeleton.rightFootTrackerBone.getTailPosition() - rightFoot.position).len()

		val leftConstraint = hpm.skeleton.leftFootBone.rotationConstraint
		val rightConstraint = hpm.skeleton.rightFootBone.rotationConstraint
		val leftDeviation =
			(hpm.skeleton.leftFootBone.getGlobalRotation() * leftConstraint.initialRotation.inv()).angleR() * FastMath.RAD_TO_DEG
		val rightDeviation =
			(hpm.skeleton.rightFootBone.getGlobalRotation() * rightConstraint.initialRotation.inv()).angleR() * FastMath.RAD_TO_DEG

		return Result(
			residualMm = maxOf(leftResidual, rightResidual) * 1000f,
			rotationDeviationDeg = maxOf(leftDeviation, rightDeviation),
		)
	}

	@Test
	fun reportCorrectionTowardInitialContribution() {
		val normal = runCase(skipCorrectionTowardInitial = false)
		val noCorrection = runCase(skipCorrectionTowardInitial = true)

		assertTrue(
			false,
			"Tracker correction diagnostic. " +
				"NORMAL=[residual=${normal.residualMm} mm, rotationDeviation=${normal.rotationDeviationDeg} deg]; " +
				"NO_CORRECTION=[residual=${noCorrection.residualMm} mm, rotationDeviation=${noCorrection.rotationDeviationDeg} deg].",
		)
	}
}
