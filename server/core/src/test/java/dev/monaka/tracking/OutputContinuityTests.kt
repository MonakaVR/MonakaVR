package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.*

class OutputContinuityTests {
	private val target = TrackerPosition.HIP
	private val space = CoordinateSpace("test-world", "rh_y_up_neg_z_forward", 0)
	private fun main(now: Long, full: Boolean = true, rotation: Boolean = true) = ResolvedTrackingPose(target, space,
		if (full) ResolvedComponent(Vector3(3f, 2f, 1f), "main", ObservationQuality.TRACKED, now) else null,
		if (rotation) ResolvedComponent(Quaternion.IDENTITY, if (full) "main" else "imu", ObservationQuality.TRACKED, now) else null,
		MainSampleState(if (full) TrackingModality.FULL else TrackingModality.ROTATION_ONLY, full, rotation))
	private fun background(now: Long, p: Vector3 = Vector3(2.5f, 2f, 1f)) = BackgroundIkResult(
		OutputPose(target, space, ResolvedComponent(p, "monaka-solver:HIP", ObservationQuality.TRACKED, now),
			ResolvedComponent(Quaternion.IDENTITY, "monaka-solver:HIP", ObservationQuality.TRACKED, now), OutputPositionSource.BACKGROUND_IK), "current_solver_frame")

	@Test fun startupWithoutPacketsDoesNotBlockFirstFullAndFullFramesDoNotSpamLogs() {
		val events = mutableListOf<OutputTransition>()
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, onTransition = events::add)
		c.update(main(0, false, false), BackgroundIkResult(null, "alignment_unverified"), 0)
		assertEquals(ContinuityState.UNAVAILABLE, c.state)
		for (now in 1L..50L) {
			assertEquals(main(now).position, c.update(main(now), background(now), now).position)
			assertEquals(ContinuityState.MAIN_DIRECT, c.state)
		}
		assertEquals(2, events.size)
		assertEquals("main", events.last().positionOwner)
		assertEquals(OutputPositionSource.RESOLVED_MAIN, events.last().outputPositionSource)
	}

	@Test fun lossUsesOnlyCurrentAlignedIkPositionAndKeepsResolverRotationOwner() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		c.update(main(0), background(0), 0)
		val fallback = c.update(main(1, false), background(1), 1)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		assertEquals(background(1).pose!!.position, fallback.position)
		assertEquals("imu", fallback.rotation!!.sourceId)
		assertEquals(TrackingModality.FULL, fallback.modality) // output validity differs from Main modality
		assertFalse(c.mainPose!!.positionValid); assertTrue(fallback.positionValid)
		for ((now, bad) in listOf(
			2L to background(1), // previous tick cannot masquerade as live IK
			3L to background(3).let { it.copy(pose = it.pose!!.copy(space = space.copy(revision = 1))) },
			4L to BackgroundIkResult(null, "alignment_unverified"),
		)) {
			val out = c.update(main(now, false), bad, now)
			assertNull(out.position); assertFalse(out.positionValid)
			assertEquals(TrackingModality.ROTATION_ONLY, out.modality)
		}
		val none = c.update(main(5, false, false), background(5), 5)
		assertNull(none.position); assertNull(none.rotation); assertEquals(ContinuityState.UNAVAILABLE, c.state)
	}

	@Test fun reacquisitionIsAnExplicitPendingBoundaryAndInjectedPolicyUsesDeterministicTime() {
		val events = mutableListOf<OutputTransition>()
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, onTransition = events::add)
		c.update(main(0), background(0), 0)
		c.update(main(1, false), background(1), 1)
		for (now in 2L..100L) {
			val out = c.update(main(now), background(now), now)
			assertEquals(ContinuityState.REACQUIRING, c.state)
			assertEquals(background(now).pose!!.position, out.position)
			assertEquals("main", out.rotation!!.sourceId)
			assertNull(c.blendProgress); assertEquals(2L, c.transitionStartedAt)
			assertEquals(.5f, c.positionResidual); assertEquals(0f, c.rotationResidual)
		}
		assertEquals(3, events.size)
		val contexts = mutableListOf<ReacquisitionContext>()
		val withPolicy = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK,
			reacquisition = ReacquisitionStrategy { context ->
				contexts += context
				if (context.now - context.startedAt < 10) ConvergenceStep(Vector3(2.75f, 2f, 1f), .5f, false)
				else ConvergenceStep(context.main.position!!.value, 1f, true)
			})
		withPolicy.update(main(0), background(0), 0)
		withPolicy.update(main(1, false), background(1), 1)
		assertEquals(Vector3(2.75f, 2f, 1f), withPolicy.update(main(2), background(2), 2).position!!.value)
		assertEquals(ContinuityState.REACQUIRING, withPolicy.state)
		val settled = withPolicy.update(main(12), background(12), 12)
		assertEquals(ContinuityState.MAIN_DIRECT, withPolicy.state)
		assertEquals(main(12).position, settled.position)
		assertEquals(2, contexts.size)
		assertEquals(1f, withPolicy.blendProgress)
		assertFails { withPolicy.update(main(11), background(11), 11) }
	}

	@Test fun directOnlyAndPauseNeverBorrowBackgroundOrHeldPosition() {
		val c = OutputContinuityController(target, ContinuityPolicy.NONE)
		c.update(main(0), background(0), 0)
		assertNull(c.update(main(1, false), background(1), 1).position)
		val paused = c.update(main(2), background(2), 2, paused = true)
		assertEquals(TrackingModality.NONE, paused.modality)
		assertNull(paused.position); assertNull(paused.rotation)
		assertEquals(ContinuityState.UNAVAILABLE, c.state)
	}

	@Test fun alignmentIsNeverInferredFromNumericallySimilarCoordinates() {
		val pose = SkeletonWorldPose(target, Vector3(1f, 2f, 3f), Quaternion.IDENTITY, 1)
		assertNull(BackgroundIkAlignment.UNVERIFIED.toMonakaWorld(pose, space))
		val confirmed = BackgroundIkAlignment.confirmedSameSpace(space)
		assertEquals(pose.position, confirmed.toMonakaWorld(pose, space)!!.position!!.value)
		assertNull(confirmed.toMonakaWorld(pose, space.copy(revision = 1)))
		assertNull(confirmed.toMonakaWorld(pose, space.copy(id = "other")))
	}
}
