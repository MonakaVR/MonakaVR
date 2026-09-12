package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKChain
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Characterizes whether the first positional IK solve starts from stale cached
 * chain rotations rather than the current FK pose.
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

		// Establish the current FK pose with positional IK disabled.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		val computedLeft = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		// Make every positional target exactly coincide with the current skeleton.
		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeft.position
		rightFoot.position = computedRight.position

		val leftBaseline = hpm.skeleton.leftFootTrackerBone.getTailPosition()
		val rightBaseline = hpm.skeleton.rightFootTrackerBone.getTailPosition()

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true

		return Fixture(hpm, leftBaseline, rightBaseline)
	}

	@Suppress("UNCHECKED_CAST")
	private fun synchronizeCachedRotations(hpm: HumanPoseManager) {
		val solver = hpm.skeleton.ikSolver
		val chainListField = solver.javaClass.getDeclaredField("chainList")
		chainListField.isAccessible = true
		val chains = chainListField.get(solver) as List<IKChain>

		val rotationsField = IKChain::class.java.getDeclaredField("rotations")
		rotationsField.isAccessible = true

		for (chain in chains) {
			val rotations = rotationsField.get(chain) as MutableList<Quaternion>
			for (index in chain.bones.indices) {
				rotations[index] = chain.bones[index].getGlobalRotation()
			}
		}
	}

	private fun solveAndMeasure(fixture: Fixture, synchronizeCache: Boolean): Drift {
		if (synchronizeCache) synchronizeCachedRotations(fixture.hpm)
		fixture.hpm.skeleton.ikSolver.solve()

		val left = fixture.hpm.skeleton.leftFootTrackerBone.getTailPosition()
		val right = fixture.hpm.skeleton.rightFootTrackerBone.getTailPosition()
		return Drift(
			leftMm = (left - fixture.leftBaseline).len() * 1000f,
			rightMm = (right - fixture.rightBaseline).len() * 1000f,
		)
	}

	@Test
	fun refreshingCachedRotationsEliminatesZeroErrorFirstSolveDrift() {
		val normalSixDof = solveAndMeasure(createZeroErrorFixture(feetHaveRotation = true), synchronizeCache = false)
		val syncedSixDof = solveAndMeasure(createZeroErrorFixture(feetHaveRotation = true), synchronizeCache = true)
		val normalPositionOnly = solveAndMeasure(createZeroErrorFixture(feetHaveRotation = false), synchronizeCache = false)
		val syncedPositionOnly = solveAndMeasure(createZeroErrorFixture(feetHaveRotation = false), synchronizeCache = true)

		val details = "normal6dof=${normalSixDof.maxMm} mm, synced6dof=${syncedSixDof.maxMm} mm, " +
			"normalPositionOnly=${normalPositionOnly.maxMm} mm, syncedPositionOnly=${syncedPositionOnly.maxMm} mm"

		assertTrue(
			normalSixDof.maxMm > 5f && normalPositionOnly.maxMm > 1f,
			"Expected the current first-solve drift to be reproducible before cache synchronization: $details",
		)
		assertTrue(
			syncedSixDof.maxMm < 0.5f && syncedPositionOnly.maxMm < 0.5f,
			"Synchronizing the IKChain rotation cache did not eliminate zero-error first-solve drift: $details",
		)
	}
}
