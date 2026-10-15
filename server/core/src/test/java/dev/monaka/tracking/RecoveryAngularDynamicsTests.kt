package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class RecoveryAngularDynamicsTests {
	private val target = TrackerPosition.HIP
	private val space = CoordinateSpace("angular-test", "rh_y_up_neg_z_forward", 0)
	private val residual = .0425f // Captured 2C-3H stationary loss orientation residual.
	private fun selected(now: Long, full: Boolean, q: Quaternion) = ResolvedTrackingPose(target, space,
		if (full) ResolvedComponent(Vector3(.17f, -.38f, -.12f), "main", ObservationQuality.TRACKED, now) else null,
		ResolvedComponent(q, if (full) "main" else "fallback", ObservationQuality.TRACKED, now),
		MainSampleState(if (full) TrackingModality.FULL else TrackingModality.ROTATION_ONLY, full, true))
	private fun background(now: Long) = BackgroundIkResult(OutputPose(target, space,
		ResolvedComponent(Vector3(.155f, -.31f, -.146f), "solver", ObservationQuality.TRACKED, now),
		ResolvedComponent(Quaternion.IDENTITY, "solver", ObservationQuality.TRACKED, now),
		OutputPositionSource.BACKGROUND_IK), "current_solver_frame")
	private fun angle(a: Quaternion, b: Quaternion) = (b / a).angleR().toDouble()
	private fun tick(c: OutputContinuityController, ms: Long, full: Boolean, q: Quaternion) =
		c.update(selected(ms * 1_000_000, full, q), background(ms * 1_000_000), ms * 1_000_000)
	private fun fallback(c: OutputContinuityController): OutputPose {
		tick(c, 0, true, Quaternion.IDENTITY)
		val before = c.lastOutput!!
		assertEquals(before.position!!.value, tick(c, 20, false, Quaternion.rotationAroundYAxis(-residual)).position!!.value)
		return tick(c, 200, false, Quaternion.rotationAroundYAxis(-residual))
	}

	@Test fun capturedSizeResidualUsesRecoveryRateDuringDwellAndRestoresOnOriginalDeadline() {
		val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		val lost = fallback(c)
		val first = tick(c, 220, true, Quaternion.IDENTITY)
		assertEquals(lost.rotation!!.value, first.rotation!!.value)
		var previous = first
		val rates = mutableListOf<Double>()
		for (ms in 240L..700L step 20) {
			val current = tick(c, ms, true, Quaternion.IDENTITY)
			rates += angle(previous.rotation!!.value, current.rotation!!.value) / .02
			assertTrue(abs(current.rotation.value.lenSq() - 1f) < 1e-6f)
			if (ms < 380) assertEquals(FusionPhase.RECOVERY_DWELL, c.fusionPhase)
			if (ms == 380L) {
				assertEquals(FusionPhase.RECOVERY_BLEND, c.fusionPhase)
				assertTrue(angle(previous.rotation.value, current.rotation.value) < 1e-6)
			}
			if (ms == 680L) {
				assertEquals(FusionPhase.FULL_6DOF, c.fusionPhase)
				assertEquals(Quaternion.IDENTITY, current.rotation.value)
			}
			previous = current
		}
		// Old dwell-driven time scale produced residual/.15 = .2833rad/s.
		assertTrue(rates.max() <= residual / .3 + 1e-5, "peak ${rates.max()}")
		assertTrue(rates.max() > .1) // Do not satisfy this by freezing/omitting orientation.
	}

	@Test fun antipodalMainSamplesFollowSameShortestArcThroughDwellBlendAndFull() {
		val a = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		val b = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		fallback(a); fallback(b)
		val desired = Quaternion.rotationAroundXAxis(.1f) * Quaternion.rotationAroundYAxis(.2f) *
			Quaternion.rotationAroundZAxis(-.3f)
		for ((index, ms) in (220L..700L step 20).withIndex()) {
			val x = tick(a, ms, true, desired)
			val y = tick(b, ms, true, if (index % 2 == 0) -desired else desired)
			assertTrue(angle(x.rotation!!.value, y.rotation!!.value) < 1e-6)
			assertEquals(x.position!!.value, y.position!!.value)
			assertEquals(a.fusionPhase, b.fusionPhase)
		}
	}

	@Test fun angularChangeLeavesPositionTrajectoryExactlyUnchanged() {
		val a = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		val b = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
		fallback(a); fallback(b)
		for (ms in 220L..700L step 20) {
			val x = tick(a, ms, true, Quaternion.IDENTITY)
			val y = tick(b, ms, true, Quaternion.rotationAroundYAxis(.5f))
			assertEquals(x.position, y.position)
			assertEquals(a.fusionPhase, b.fusionPhase)
		}
	}

	@Test fun relossDuringDwellOrBlendStartsAtLastEmittedOrientationAndRecoversWithoutNan() {
		for (lossMs in listOf(280L, 460L)) {
			val c = OutputContinuityController(target, ContinuityPolicy.BACKGROUND_IK)
			fallback(c)
			for (ms in 220L until lossMs step 20) tick(c, ms, true, Quaternion.IDENTITY)
			val before = c.lastOutput!!
			val lost = tick(c, lossMs, false, Quaternion.rotationAroundYAxis(-residual))
			assertEquals(before.position!!.value, lost.position!!.value)
			assertTrue(angle(before.rotation!!.value, lost.rotation!!.value) < 1e-6)
			assertEquals(FusionPhase.FALLBACK_IK, c.fusionPhase)
			for (ms in lossMs + 20..lossMs + 700 step 20) {
				val out = tick(c, ms, true, Quaternion.IDENTITY)
				assertTrue(listOf(out.rotation!!.value.x, out.rotation.value.y, out.rotation.value.z,
					out.rotation.value.w).all { it.isFinite() })
			}
			assertEquals(FusionPhase.FULL_6DOF, c.fusionPhase)
		}
	}
}
