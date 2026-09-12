package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Diagnostic for the target construction used by the convergence tests.
 *
 * It records whether the computed foot output remains coincident with the raw
 * positional tracker after resetOffsets() calibration, and whether constructing
 * a moved target from the computed output describes the same point as moving the
 * raw tracker by the same delta.
 */
class PositionalIKCalibrationTargetDiagnosticsTests {

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "calibration-target-${position.name}",
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

	private fun vecMm(v: Vector3): String =
		"(${v.x * 1000f},${v.y * 1000f},${v.z * 1000f})mm"

	private fun runCase(feetHaveRotation: Boolean): String {
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

		val leftRawBeforeCalibration = leftFoot.position
		val rightRawBeforeCalibration = rightFoot.position

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val leftCalibrationDelta = computedLeft.position - leftFoot.position
		val rightCalibrationDelta = computedRight.position - rightFoot.position

		val leftTargetFromComputed = computedLeft.position + Vector3(0.05f, 0f, 0f)
		val rightTargetFromComputed = computedRight.position + Vector3(-0.05f, 0f, 0f)
		val leftTargetFromRaw = leftFoot.position + Vector3(0.05f, 0f, 0f)
		val rightTargetFromRaw = rightFoot.position + Vector3(-0.05f, 0f, 0f)

		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return "leftRawChangedDuringCalibration=${vecMm(leftFoot.position - Vector3(leftRawBeforeCalibration.x + 0.05f, leftRawBeforeCalibration.y, leftRawBeforeCalibration.z))} " +
			"rightRawChangedDuringCalibration=${vecMm(rightFoot.position - Vector3(rightRawBeforeCalibration.x - 0.05f, rightRawBeforeCalibration.y, rightRawBeforeCalibration.z))} " +
			"leftCalibrationDelta=${vecMm(leftCalibrationDelta)} " +
			"rightCalibrationDelta=${vecMm(rightCalibrationDelta)} " +
			"leftComputedTargetMinusMovedRaw=${vecMm(leftTargetFromComputed - leftFoot.position)} " +
			"rightComputedTargetMinusMovedRaw=${vecMm(rightTargetFromComputed - rightFoot.position)} " +
			"leftRawTargetMinusMovedRaw=${vecMm(leftTargetFromRaw - leftFoot.position)} " +
			"rightRawTargetMinusMovedRaw=${vecMm(rightTargetFromRaw - rightFoot.position)}"
	}

	@Test
	fun reportCalibrationBaselineAndTargetConstruction() {
		val sixDof = runCase(feetHaveRotation = true)
		val positionOnly = runCase(feetHaveRotation = false)

		assertTrue(
			false,
			"Calibration target diagnostic. SIX_DOF=[$sixDof]; POSITION_ONLY=[$positionOnly].",
		)
	}
}
