package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.tracking.processor.Constraint
import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test

/**
 * Diagnostic that isolates the 15-degree tracker-backed rotation clamp from the
 * rest of the 6DoF positional IK path. The clamp angle is widened through
 * reflection only for the test fixture; production code is unchanged.
 */
class PositionalIKTrackerRotationLimitDiagnosticsTests {

	private data class Result(
		val residualMm: Float,
		val rotationDeviationDeg: Float,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "tracker-rotation-limit-${position.name}",
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

	private fun setTrackerDeviationLimit(constraint: Constraint, degrees: Float) {
		val field = Constraint::class.java.getDeclaredField("maxDeviationFromTrackerRad")
		field.isAccessible = true
		field.setFloat(constraint, degrees * FastMath.DEG_TO_RAD)
	}

	private fun runCase(maxDeviationDeg: Float): Result {
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

		setTrackerDeviationLimit(hpm.skeleton.leftFootBone.rotationConstraint, maxDeviationDeg)
		setTrackerDeviationLimit(hpm.skeleton.rightFootBone.rotationConstraint, maxDeviationDeg)

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
	fun reportTrackerRotationLimitContribution(reporter: org.junit.jupiter.api.TestReporter) {
		val limit15 = runCase(15f)
		val limit30 = runCase(30f)
		val limit90 = runCase(90f)
		val limit180 = runCase(180f)

		reporter.publishEntry(
			"IK diagnostic",
			"Tracker rotation-limit diagnostic. " +
				"15deg=[residual=${limit15.residualMm} mm, rotationDeviation=${limit15.rotationDeviationDeg} deg]; " +
				"30deg=[residual=${limit30.residualMm} mm, rotationDeviation=${limit30.rotationDeviationDeg} deg]; " +
				"90deg=[residual=${limit90.residualMm} mm, rotationDeviation=${limit90.rotationDeviationDeg} deg]; " +
				"180deg=[residual=${limit180.residualMm} mm, rotationDeviation=${limit180.rotationDeviationDeg} deg].",
		)
	}
}
