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
		assertEquals(main(0).position!!.value, fallback.position!!.value) // loss begins at the emitted pose
		assertEquals("imu", fallback.rotation!!.sourceId)
		assertEquals(TrackingModality.FULL, fallback.modality) // output validity differs from Main modality
		assertFalse(c.mainPose!!.positionValid); assertTrue(fallback.positionValid)
		assertEquals(background(150_000_001).pose!!.position!!.value,
			c.update(main(150_000_001, false), background(150_000_001), 150_000_001).position!!.value)
		for ((now, bad) in listOf(
			150_000_002L to background(1), // previous tick cannot masquerade as live IK
			150_000_003L to background(150_000_003).let { it.copy(pose = it.pose!!.copy(space = space.copy(revision = 1))) },
			150_000_004L to BackgroundIkResult(null, "alignment_unverified"),
		)) {
			val out = c.update(main(now, false), bad, now)
			assertNull(out.position); assertFalse(out.positionValid)
			assertEquals(TrackingModality.ROTATION_ONLY, out.modality)
		}
		val none = c.update(main(150_000_005, false, false), background(150_000_005), 150_000_005)
		assertNull(none.position); assertNull(none.rotation); assertEquals(ContinuityState.UNAVAILABLE, c.state)
	}

	@Test fun stableFullDwellThenPositionAndRotationConvergeOnDeterministicClock() {
		val events = mutableListOf<OutputTransition>()
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(3, 10, 2), events::add)
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		val loss = c.update(main(3_000_000, false), background(3_000_000), 3_000_000)
		assertEquals(background(3_000_000).pose!!.position!!.value, loss.position!!.value)
		assertEquals(loss.position!!.value, c.update(main(4_000_000), background(4_000_000), 4_000_000).position!!.value)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		val duringDwell = c.update(main(6_000_000),
			background(6_000_000, Vector3(2.6f, 2f, 1f)), 6_000_000)
		assertTrue(duringDwell.position!!.value.x > 2.5f && duringDwell.position!!.value.x < 2.6f)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		val first = c.update(main(7_000_000), background(7_000_000), 7_000_000)
		assertEquals(duringDwell.position!!.value, first.position!!.value)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		val half = c.update(main(12_000_000), background(12_000_000), 12_000_000)
		assertEquals(first.position!!.value * .5f + main(12_000_000).position!!.value * .5f, half.position!!.value)
		assertEquals(OutputPositionSource.CONVERGENCE, half.positionSource)
		assertEquals(.5f, c.blendProgress)
		val settled = c.update(main(17_000_000), background(17_000_000), 17_000_000)
		assertEquals(ContinuityState.MAIN_DIRECT, c.state)
		assertEquals(main(17_000_000).position, settled.position)
		assertEquals(main(17_000_000).rotation, settled.rotation)
		assertEquals(listOf(ContinuityState.MAIN_DIRECT, ContinuityState.FALLBACK_ACTIVE,
			ContinuityState.FALLBACK_ACTIVE, ContinuityState.REACQUIRING, ContinuityState.MAIN_DIRECT), events.map { it.state })
		assertFails { c.update(main(16_000_000), background(16_000_000), 16_000_000) }
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

	@Test fun oneFullPacketDoesNotReacquireAndRelossReturnsFromLastEmittedPose() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(3, 10, 2))
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		c.update(main(3_000_000, false), background(3_000_000), 3_000_000)
		val onePacket = c.update(main(4_000_000), background(4_000_000), 4_000_000)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		c.update(main(4_000_000), background(8_000_000), 8_000_000) // same accepted sample, later server tick
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		val lostAgain = c.update(main(9_000_000, false), background(9_000_000), 9_000_000)
		assertEquals(onePacket.position!!.value, lostAgain.position!!.value)
		c.update(main(10_000_000), background(10_000_000), 10_000_000)
		val start = c.update(main(13_000_000), background(13_000_000), 13_000_000)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		val halfway = c.update(main(18_000_000), background(18_000_000), 18_000_000)
		assertTrue(halfway.position!!.value.x > start.position!!.value.x)
		assertTrue(halfway.position!!.value.x < main(18_000_000).position!!.value.x)
		val backToFallback = c.update(main(19_000_000, false), background(19_000_000), 19_000_000)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		assertEquals(halfway.position!!.value, backToFallback.position!!.value)
		assertEquals(OutputPositionSource.BACKGROUND_IK, backToFallback.positionSource)
		assertEquals(background(21_000_000).pose!!.position!!.value,
			c.update(main(21_000_000, false), background(21_000_000), 21_000_000).position!!.value)
	}

	@Test fun acceptedFullSamplesMustSpanDwellEvenWhenServerTicksContinue() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(150, 20, 10))
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		c.update(main(11_000_000, false), background(11_000_000), 11_000_000)
		c.update(main(20_000_000), background(20_000_000), 20_000_000) // accepted FULL A
		c.update(main(21_000_000), background(21_000_000), 21_000_000) // accepted FULL B, only 1ms later
		for (now in listOf(50_000_000L, 150_000_000L, 220_000_000L)) {
			val heldSample = c.update(main(21_000_000), background(now), now)
			assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
			assertTrue(heldSample.positionValid && heldSample.rotationValid)
		}
		val before = c.lastOutput!!
		val reacquiring = c.update(main(170_000_000), background(221_000_000), 221_000_000) // accepted C spans 150ms
		assertEquals(ContinuityState.REACQUIRING, c.state)
		assertEquals(before.position!!.value, reacquiring.position!!.value)
		assertEquals(before.rotation!!.value, reacquiring.rotation!!.value)
	}

	@Test fun missingBackgroundDuringDwellKeepsOutputAndDoesNotResetFullStability() {
		val events = mutableListOf<OutputTransition>()
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(150, 20, 10), events::add)
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		c.update(main(11_000_000, false), background(11_000_000), 11_000_000)
		c.update(main(20_000_000), background(20_000_000), 20_000_000)
		val beforeMissing = c.lastOutput!!
		val missing = BackgroundIkResult(null, "no_valid_root_anchor")
		val duringMissing = c.update(main(21_000_000), missing, 21_000_000)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		assertTrue(duringMissing.positionValid && duringMissing.rotationValid)
		assertEquals(beforeMissing.position!!.value, duringMissing.position!!.value)
		assertEquals("main_full_dwell_background_missing", events.last().reason)
		val started = c.update(main(170_000_000), missing, 171_000_000)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		assertEquals(duringMissing.position!!.value, started.position!!.value)
		assertTrue(started.positionValid && started.rotationValid)
	}

	@Test fun unavailableWithoutBackgroundRecoversOnlyAfterAcceptedFullObservationsSpanDwell() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(150, 20, 10))
		val missing = BackgroundIkResult(null, "alignment_unverified")
		c.update(main(0), background(0), 0)
		val lost = c.update(main(1_000_000, false, false), missing, 1_000_000)
		assertEquals(ContinuityState.UNAVAILABLE, c.state)
		assertFalse(lost.positionValid)
		assertFalse(lost.rotationValid)

		for ((sampleAt, now) in listOf(
			20_000_000L to 20_000_000L,
			21_000_000L to 21_000_000L,
			21_000_000L to 200_000_000L,
		)) {
			val unavailable = c.update(main(sampleAt), missing, now)
			assertEquals(ContinuityState.UNAVAILABLE, c.state)
			assertFalse(unavailable.positionValid)
			assertFalse(unavailable.rotationValid)
		}

		val recovered = c.update(main(170_000_000), missing, 201_000_000)
		assertEquals(ContinuityState.MAIN_DIRECT, c.state)
		assertEquals(main(170_000_000).position, recovered.position)
		assertEquals(main(170_000_000).rotation, recovered.rotation)
		assertEquals(OutputPositionSource.RESOLVED_MAIN, recovered.positionSource)
		assertNull(c.blendProgress) // There was no valid pose to blend from.
	}

	@Test fun missingBackgroundAfterReacquisitionStartsDoesNotInterruptConvergence() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(2, 10, 1))
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		c.update(main(2_000_000, false), background(2_000_000), 2_000_000)
		c.update(main(3_000_000), background(3_000_000), 3_000_000)
		val start = c.update(main(5_000_000), background(5_000_000), 5_000_000)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		val missing = BackgroundIkResult(null, "alignment_unverified")
		val half = c.update(main(10_000_000), missing, 10_000_000)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		assertTrue(half.position!!.value.x > start.position!!.value.x)
		assertTrue(half.position!!.value.x < main(10_000_000).position!!.value.x)
		val complete = c.update(main(15_000_000), missing, 15_000_000)
		assertEquals(ContinuityState.MAIN_DIRECT, c.state)
		assertEquals(main(15_000_000).position, complete.position)
		assertEquals(main(15_000_000).rotation, complete.rotation)
	}

	@Test fun dwellRotationTracksCurrentResolverSelectionInsteadOfFreezing() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(150, 20, 1))
		c.update(main(0), background(0), 0)
		c.update(main(1_000_000, false), background(1_000_000), 1_000_000)
		c.update(main(2_000_000, false), background(2_000_000), 2_000_000)
		fun selected(sampleAt: Long, angle: Float) = main(sampleAt).copy(rotation =
			ResolvedComponent(Quaternion.rotationAroundYAxis(angle), "main", ObservationQuality.TRACKED, sampleAt))
		val a = c.update(selected(10_000_000, .2f), background(10_000_000), 10_000_000)
		val b = c.update(selected(50_000_000, .4f), background(50_000_000), 50_000_000)
		val d = c.update(selected(100_000_000, .6f), background(100_000_000), 100_000_000)
		assertEquals(ContinuityState.FALLBACK_ACTIVE, c.state)
		assertEquals(Quaternion.IDENTITY, a.rotation!!.value)
		assertTrue(kotlin.math.abs(b.rotation!!.value.dot(a.rotation.value)) < .9999f)
		assertTrue(kotlin.math.abs(d.rotation!!.value.dot(b.rotation.value)) < .9999f)
		assertEquals("main", b.rotationOwner)
		assertEquals("main", d.rotationOwner)
	}

	@Test fun rotationConvergenceTakesShortestPathAndFullToNoneInvalidatesOutput() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK, ContinuityTuning(1, 10, 1))
		c.update(main(0), background(0), 0)
		val none = c.update(main(1_000_000, false, false), background(1_000_000), 1_000_000)
		assertEquals(ContinuityState.UNAVAILABLE, c.state)
		assertFalse(none.positionValid); assertFalse(none.rotationValid)
		val fallback = c.update(main(2_000_000, false), background(2_000_000), 2_000_000)
		assertEquals(background(2_000_000).pose!!.position!!.value, fallback.position!!.value)
		val desired = -Quaternion.rotationAroundYAxis(1f)
		fun targetAt(now: Long) = main(now).copy(rotation = ResolvedComponent(desired, "main", ObservationQuality.TRACKED, now))
		c.update(targetAt(3_000_000), background(3_000_000), 3_000_000)
		val first = c.update(targetAt(4_000_000), background(4_000_000), 4_000_000)
		assertEquals(fallback.rotation!!.value, first.rotation!!.value)
		val half = c.update(targetAt(9_000_000), background(9_000_000), 9_000_000)
		val expected = Quaternion.rotationAroundYAxis(.5f)
		assertTrue(kotlin.math.abs(half.rotation!!.value.dot(expected)) > .999f)
		assertEquals(ContinuityState.REACQUIRING, c.state)
		val full = c.update(targetAt(14_000_000), background(14_000_000), 14_000_000)
		assertEquals(desired, full.rotation!!.value)
		assertEquals(ContinuityState.MAIN_DIRECT, c.state)
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
