package dev.slimevr.unit

import dev.slimevr.tracking.processor.HumanPoseManager
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Characterization tests for SlimeVR's existing positional IK path.
 *
 * These tests intentionally distinguish the production HumanPoseManager.update()
 * path from an explicit IKSolver.solve() call. This tells us whether a failure is
 * caused by the solver itself or by the solver not being wired into pose updates.
 */
class PositionalIKIntegrationTests {

	@Test
	fun hipPositionalConstraintIsAppliedDuringPoseUpdate() {
		// One HMD/root plus one external positional+rotational tracker on the hip.
		// This is the minimum useful 6DoF constraint configuration for the current
		// IKSolver without involving feet, leg tweaks, or multiple constraints.
		val trackers = TestTrackerSet(positional = true)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Establish a clean FK baseline before enabling positional IK. This avoids
		// solving against the tracker's default zero position before its mounting
		// offset has been initialized.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")

		trackers.hip.position = computedHip.position
		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.update()

		val baseline = computedHip.position

		// Move only the external 6DoF constraint. If IKSolver is actually part of
		// HumanSkeleton.updatePose(), the computed skeleton hip must respond.
		trackers.hip.position += Vector3(0.10f, 0f, 0f)
		repeat(5) {
			hpm.update()
		}

		val displacementX = computedHip.position.x - baseline.x
		assertTrue(
			displacementX > 0.005f,
			"Positional hip constraint did not affect the computed skeleton during pose update. " +
				"Expected positive X displacement, got $displacementX m.",
		)
	}

	@Test
	fun hipPositionalConstraintRespondsWhenSolverIsCalledExplicitly() {
		val trackers = TestTrackerSet(positional = true)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Build the native FK pose first, then align the positional tracker with the
		// generated hip tracker so resetOffsets() starts from a zero-error baseline.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()
		trackers.hip.position = hpm.skeleton.hipTrackerBone.getPosition()

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.skeleton.ikSolver.solve()

		val baseline = hpm.skeleton.hipTrackerBone.getPosition()

		// Bypass HumanSkeleton.updatePose() and invoke the existing solver directly.
		// If this test passes while the update-path test fails, the CCDIK solver is
		// operational and the missing production wiring is isolated as the defect.
		trackers.hip.position += Vector3(0.10f, 0f, 0f)
		repeat(5) {
			hpm.skeleton.ikSolver.solve()
		}

		val displacementX = hpm.skeleton.hipTrackerBone.getPosition().x - baseline.x
		assertTrue(
			displacementX > 0.005f,
			"Explicit IKSolver.solve() did not move the hip constraint. " +
				"Expected positive X displacement, got $displacementX m.",
		)
	}

	@Test
	fun explicitSolverMustRunBeforeComputedTrackersAreRefreshed() {
		val trackers = TestTrackerSet(positional = true)
		val hpm = HumanPoseManager(listOf(trackers.head, trackers.hip))
		hpm.setLegTweaksEnabled(false)

		trackers.head.position = Vector3(0f, 1.7f, 0f)
		trackers.head.setRotation(Quaternion.IDENTITY)
		trackers.hip.setRotation(Quaternion.IDENTITY)

		// Establish the native FK pose and align the external hip constraint with
		// the generated hip tracker before initializing the IK mounting offset.
		hpm.skeleton.ikSolver.enabled = false
		hpm.update()

		val computedHip = hpm.skeleton.computedHipTracker
			?: error("Computed hip tracker was not initialized")
		trackers.hip.position = hpm.skeleton.hipTrackerBone.getPosition()

		hpm.skeleton.ikSolver.resetOffsets()
		hpm.skeleton.ikSolver.enabled = true
		hpm.skeleton.ikSolver.solve()

		val baselineBoneX = hpm.skeleton.hipTrackerBone.getPosition().x

		// Run the normal pose update after moving the external constraint. The
		// current production path refreshes computed trackers here but does not run
		// positional IK.
		trackers.hip.position += Vector3(0.10f, 0f, 0f)
		hpm.update()
		val computedBeforeExplicitSolveX = computedHip.position.x

		// Solving now should move the skeleton bone, but it is too late for the
		// already-refreshed computed tracker in this tick.
		repeat(5) {
			hpm.skeleton.ikSolver.solve()
		}

		val boneDisplacementX = hpm.skeleton.hipTrackerBone.getPosition().x - baselineBoneX
		val computedAfterExplicitSolveX = computedHip.position.x

		assertTrue(
			boneDisplacementX > 0.005f,
			"Explicit IKSolver.solve() did not move the hip bone. " +
				"Expected positive X displacement, got $boneDisplacementX m.",
		)
		assertTrue(
			abs(computedAfterExplicitSolveX - computedBeforeExplicitSolveX) < 0.000001f,
			"Computed hip changed after an explicit solve that ran after computed trackers were refreshed. " +
				"Before=$computedBeforeExplicitSolveX, after=$computedAfterExplicitSolveX.",
		)
	}
}
