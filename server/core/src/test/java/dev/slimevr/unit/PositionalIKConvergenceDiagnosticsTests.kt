package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test

/**
 * Temporary characterization test used to distinguish instability inside repeated
 * CCDIK solves from instability introduced by rebuilding the FK pose every tick.
 */
class PositionalIKConvergenceDiagnosticsTests {

	private data class Fixture(
		val hpm: HumanPoseManager,
		val hipTarget: Vector3,
		val leftTarget: Vector3,
		val rightTarget: Vector3,
	)

	private fun createMovedThreePointFixture(): Fixture {
		val trackers = TestTrackerSet(positional = true)
		val leftFoot = trackers.mkTrack(7, TrackerPosition.LEFT_FOOT)
		val rightFoot = trackers.mkTrack(8, TrackerPosition.RIGHT_FOOT)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip, leftFoot, rightFoot))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)
		leftFoot.setRotation(Quaternion.IDENTITY)
		rightFoot.setRotation(Quaternion.IDENTITY)

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

		val hipTarget = computedHip.position
		val leftTarget = computedLeftFoot.position + Vector3(0.05f, 0f, 0f)
		val rightTarget = computedRightFoot.position + Vector3(-0.05f, 0f, 0f)

		leftFoot.position += Vector3(0.05f, 0f, 0f)
		rightFoot.position += Vector3(-0.05f, 0f, 0f)

		return Fixture(hpm, hipTarget, leftTarget, rightTarget)
	}

	private data class Residuals(val hip: Float, val left: Float, val right: Float) {
		val max: Float
			get() = maxOf(hip, left, right)

		fun mmString(tick: Int): String =
			"$tick: hip=${hip * 1000f} mm, left=${left * 1000f} mm, " +
				"right=${right * 1000f} mm, max=${max * 1000f} mm"
	}

	private fun boneResiduals(fixture: Fixture): Residuals {
		val skeleton = fixture.hpm.skeleton
		return Residuals(
			hip = (skeleton.hipTrackerBone.getTailPosition() - fixture.hipTarget).len(),
			left = (skeleton.leftFootTrackerBone.getTailPosition() - fixture.leftTarget).len(),
			right = (skeleton.rightFootTrackerBone.getTailPosition() - fixture.rightTarget).len(),
		)
	}

	@Test
	fun reportFullTicksVersusRepeatedSolverCalls(reporter: org.junit.jupiter.api.TestReporter) {
		val fullTickFixture = createMovedThreePointFixture()
		val fullTickTrace = mutableListOf<String>()
		repeat(10) { index ->
			fullTickFixture.hpm.update()
			fullTickTrace += boneResiduals(fullTickFixture).mmString(index + 1)
		}

		val solverOnlyFixture = createMovedThreePointFixture()
		val solverOnlyTrace = mutableListOf<String>()
		repeat(10) { index ->
			solverOnlyFixture.hpm.skeleton.ikSolver.solve()
			solverOnlyTrace += boneResiduals(solverOnlyFixture).mmString(index + 1)
		}

		reporter.publishEntry(
			"IK diagnostic",
			"Multi-constraint convergence diagnostic. " +
				"FULL_TICKS=[${fullTickTrace.joinToString(" | ")}]; " +
				"SOLVER_ONLY=[${solverOnlyTrace.joinToString(" | ")}].",
		)
	}
}
