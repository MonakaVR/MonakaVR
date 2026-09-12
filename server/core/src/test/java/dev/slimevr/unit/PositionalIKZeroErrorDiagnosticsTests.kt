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
 * Diagnostic for the pose shift observed immediately after positional tracker
 * offsets are reset while the external trackers themselves remain stationary.
 * It separates ordinary FK/update drift from drift introduced by IKSolver.solve().
 */
class PositionalIKZeroErrorDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftFoot: Tracker,
		val rightFoot: Tracker,
		val baselineLeft: Vector3,
		val baselineRight: Vector3,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "zero-error-${position.name}",
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

	private fun createAlignedFixture(feetHaveRotation: Boolean): Fixture {
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

		return Fixture(
			hpm = hpm,
			leftFoot = leftFoot,
			rightFoot = rightFoot,
			baselineLeft = hpm.skeleton.leftFootTrackerBone.getTailPosition(),
			baselineRight = hpm.skeleton.rightFootTrackerBone.getTailPosition(),
		)
	}

	private fun vecMm(v: Vector3): String =
		"(${v.x * 1000f},${v.y * 1000f},${v.z * 1000f})mm"

	private fun boneDelta(fixture: Fixture): String {
		val left = fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition() - fixture.baselineLeft
		val right = fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition() - fixture.baselineRight
		val leftRawDelta = fixture.leftFoot.position - fixture.baselineLeft
		val rightRawDelta = fixture.rightFoot.position - fixture.baselineRight
		return "leftBone=${vecMm(left)} rightBone=${vecMm(right)} " +
			"leftRawVsBaseline=${vecMm(leftRawDelta)} rightRawVsBaseline=${vecMm(rightRawDelta)}"
	}

	private fun runControl(feetHaveRotation: Boolean): String {
		val fixture = createAlignedFixture(feetHaveRotation)
		fixture.hpm.update()
		return boneDelta(fixture)
	}

	private fun runDirectSolve(feetHaveRotation: Boolean): String {
		val fixture = createAlignedFixture(feetHaveRotation)
		fixture.hpm.skeleton.ikSolver.resetOffsets()
		fixture.hpm.skeleton.ikSolver.enabled = true
		fixture.hpm.skeleton.ikSolver.solve()
		return boneDelta(fixture)
	}

	private fun runFullTick(feetHaveRotation: Boolean): String {
		val fixture = createAlignedFixture(feetHaveRotation)
		fixture.hpm.skeleton.ikSolver.resetOffsets()
		fixture.hpm.skeleton.ikSolver.enabled = true
		fixture.hpm.update()
		return boneDelta(fixture)
	}

	@Test
	fun reportZeroErrorCalibrationDriftSource() {
		val sixDofControl = runControl(feetHaveRotation = true)
		val sixDofDirect = runDirectSolve(feetHaveRotation = true)
		val sixDofFull = runFullTick(feetHaveRotation = true)
		val positionOnlyControl = runControl(feetHaveRotation = false)
		val positionOnlyDirect = runDirectSolve(feetHaveRotation = false)
		val positionOnlyFull = runFullTick(feetHaveRotation = false)

		assertTrue(
			false,
			"Zero-error calibration diagnostic. " +
				"SIX_DOF_CONTROL=[$sixDofControl]; SIX_DOF_DIRECT_SOLVE=[$sixDofDirect]; SIX_DOF_FULL_TICK=[$sixDofFull]; " +
				"POSITION_ONLY_CONTROL=[$positionOnlyControl]; POSITION_ONLY_DIRECT_SOLVE=[$positionOnlyDirect]; " +
				"POSITION_ONLY_FULL_TICK=[$positionOnlyFull].",
		)
	}
}
