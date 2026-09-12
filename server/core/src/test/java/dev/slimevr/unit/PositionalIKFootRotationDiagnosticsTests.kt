package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.processor.skeleton.IKChain
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 * Temporary characterization test used to determine whether multi-constraint
 * drift is specific to the tracked-rotation path in IKChain or is present in the
 * generic positional CCDIK path as well.
 */
class PositionalIKFootRotationDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val leftTarget: Vector3,
		val rightTarget: Vector3,
	)

	private fun makeFootTracker(id: Int, position: TrackerPosition, hasRotation: Boolean): Tracker {
		val tracker = Tracker(
			device = null,
			id = id,
			name = "diag-${position.name}",
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

	private fun createFixture(feetHaveRotation: Boolean): Fixture {
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
		val computedLeftFoot = hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val computedRightFoot = hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")

		trackers.hip.position = computedHip.position
		leftFoot.position = computedLeftFoot.position
		rightFoot.position = computedRightFoot.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val leftTarget = computedLeftFoot.position + Vector3(0.05f, 0f, 0f)
		val rightTarget = computedRightFoot.position + Vector3(-0.05f, 0f, 0f)
		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return Fixture(hpm, leftTarget, rightTarget)
	}

	private fun outputResidualMm(fixture: Fixture): Float {
		val left = fixture.hpm.skeleton.computedLeftFootTracker
			?: error("Computed left foot tracker was not initialized")
		val right = fixture.hpm.skeleton.computedRightFootTracker
			?: error("Computed right foot tracker was not initialized")
		return maxOf(
			(left.position - fixture.leftTarget).len(),
			(right.position - fixture.rightTarget).len(),
		) * 1000f
	}

	@Suppress("UNCHECKED_CAST")
	private fun solverTrace(hpm: HumanPoseManager): String {
		val solver = hpm.skeleton.ikSolver
		val field = solver.javaClass.getDeclaredField("chainList")
		field.isAccessible = true
		val chains = field.get(solver) as List<IKChain>
		return chains
			.filter { it.tailConstraint != null }
			.joinToString(", ") { chain ->
				val tracker = chain.tailConstraint!!
				val residualMm = sqrt(chain.distToTargetSqr) * 1000f
				"${tracker.trackerPosition}:${chain.bones.last().boneType}=$residualMm mm"
			}
	}

	private fun runTrace(feetHaveRotation: Boolean): String {
		val fixture = createFixture(feetHaveRotation)
		val trace = mutableListOf<String>()
		repeat(10) { index ->
			fixture.hpm.update()
			trace += "${index + 1}: outputMax=${outputResidualMm(fixture)} mm, " +
				"solver=[${solverTrace(fixture.hpm)}]"
		}
		return trace.joinToString(" | ")
	}

	@Test
	fun reportSixDofFeetVersusPositionOnlyFeet() {
		val sixDofTrace = runTrace(feetHaveRotation = true)
		val positionOnlyTrace = runTrace(feetHaveRotation = false)

		assertTrue(
			false,
			"Foot rotation diagnostic. SIX_DOF=[$sixDofTrace]; " +
				"POSITION_ONLY=[$positionOnlyTrace].",
		)
	}
}
