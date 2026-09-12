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
 * Regression coverage for the first positional IK solve starting from the current
 * FK pose rather than stale rotations cached when the IK chains were built.
 */
class PositionalIKRotationCacheDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftBaseline: Vector3,
		val rightBaseline: Vector3,
	)

	private data class Drift(val leftMm: Float, val rightMm: Float) {
		val maxMm: Float
			get() = maxOf(leftMm, rightMm)
	}

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "rotation-cache-${position.name}",
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

	private fun createZeroErrorFixture(feetHaveRotation: Boolean): Fixture {
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

		val leftBaseline = hpm.skeleton.leftFootTrackerBone.getTailPosition()
		val rightBaseline = hpm.skeleton.rightFootTrackerBone.getTailPosition()

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true

		return Fixture(hpm, leftBaseline, rightBaseline)
	}

	private fun measure(fixture: Fixture): Drift {
		val left = fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition()
		val right = fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition()
		return Drift(
			leftMm = (left - fixture.leftBaseline).len() * 1000f,
			rightMm = (right - fixture.rightBaseline).len() * 1000f,
		)
	}

	private fun directSolveDrift(feetHaveRotation: Boolean): Drift {
		val fixture = createZeroErrorFixture(feetHaveRotation)
		fixture.hpm.skeleton.ikSolver.solve()
		return measure(fixture)
	}

	private fun fullTickDrift(feetHaveRotation: Boolean): Drift {
		val fixture = createZeroErrorFixture(feetHaveRotation)
		fixture.hpm.update()
		return measure(fixture)
	}

	@Test
	fun zeroErrorFirstSolveDoesNotDriftAfterRotationCacheRefresh() {
		val directSixDof = directSolveDrift(feetHaveRotation = true)
		val fullSixDof = fullTickDrift(feetHaveRotation = true)
		val directPositionOnly = directSolveDrift(feetHaveRotation = false)
		val fullPositionOnly = fullTickDrift(feetHaveRotation = false)

		val details = "direct6dof=${directSixDof.maxMm} mm, full6dof=${fullSixDof.maxMm} mm, " +
			"directPositionOnly=${directPositionOnly.maxMm} mm, fullPositionOnly=${fullPositionOnly.maxMm} mm"

		assertTrue(
			directSixDof.maxMm < 0.5f && fullSixDof.maxMm < 0.5f &&
				directPositionOnly.maxMm < 0.5f && fullPositionOnly.maxMm < 0.5f,
			"A zero-error first positional IK solve drifted away from its calibrated pose: $details",
		)
	}
}
