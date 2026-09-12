package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Diagnostic for the residual floor that appears only when foot rotation is also
 * constrained. The current IKChain tracked-rotation offset assumes the constrained
 * bone extends along local -Y; foot bones are configured along local -Z. Varying
 * FOOT_LENGTH determines whether the residual scales with that geometry term.
 */
class PositionalIKFootGeometryDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftFoot: Tracker,
		val rightFoot: Tracker,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "foot-geometry-${position.name}",
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

	private fun createMovedFixture(feetHaveRotation: Boolean, footLength: Float): Fixture {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = makeFootTracker(7, TrackerPosition.LEFT_FOOT, feetHaveRotation)
		val rightFoot = makeFootTracker(8, TrackerPosition.RIGHT_FOOT, feetHaveRotation)
		val hpm = HumanPoseManager(
			listOf(trackers.head, trackers.hip, leftFoot, rightFoot),
			mapOf(SkeletonConfigOffsets.FOOT_LENGTH to footLength),
		)
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

		return Fixture(hpm, leftFoot, rightFoot)
	}

	private fun steadyResidualMm(feetHaveRotation: Boolean, footLength: Float): Float {
		val fixture = createMovedFixture(feetHaveRotation, footLength)
		repeat(10) {
			fixture.hpm.update()
		}

		val computedLeft = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRight = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		return maxOf(
			(computedLeft.position - fixture.leftFoot.position).len(),
			(computedRight.position - fixture.rightFoot.position).len(),
		) * 1000f
	}

	@Test
	fun reportResidualVersusFootLength() {
		val sixDof0 = steadyResidualMm(feetHaveRotation = true, footLength = 0.0f)
		val sixDof25 = steadyResidualMm(feetHaveRotation = true, footLength = 0.025f)
		val sixDof50 = steadyResidualMm(feetHaveRotation = true, footLength = 0.05f)
		val sixDof100 = steadyResidualMm(feetHaveRotation = true, footLength = 0.10f)
		val positionOnly50 = steadyResidualMm(feetHaveRotation = false, footLength = 0.05f)

		assertTrue(
			false,
			"Foot geometry diagnostic. " +
				"SIX_DOF=[0mm:$sixDof0 mm, 25mm:$sixDof25 mm, 50mm:$sixDof50 mm, 100mm:$sixDof100 mm]; " +
				"POSITION_ONLY=[50mm:$positionOnly50 mm].",
		)
	}
}
