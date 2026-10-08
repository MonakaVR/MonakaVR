package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import kotlin.test.*

class SlimeIndependentImuOrientationCaptureTests {
	private fun tracker() = Tracker(null, 1, "receipt", trackerPosition = null, hasRotation = true).also { it.setRotation(Quaternion.IDENTITY) }
	private fun capture() = SlimeIndependentImuOrientationCapture { error("Tick capture must not read receipt clock") }
	private fun receipt(t: Tracker) = t.correctionOrientationSample()!!.receivedAtSystemNanos
	private fun at(c: SlimeIndependentImuOrientationCapture, t: Tracker, now: Long, cutoff: Long) =
		assertIs<SlimeImuCaptureResult.Available>(c.captureAtTick(t, now, cutoff)).sample

	@Test fun postCutoffRejectsWithoutBackdatingAndNextTickAdmits() {
		val t = tracker(); val r = receipt(t); val c = capture()
		assertEquals(SlimeImuCaptureRejection.SAMPLE_AFTER_TICK_CUTOFF,
			assertIs<SlimeImuCaptureResult.Unavailable>(c.captureAtTick(t, 100, r - 1)).reason)
		assertEquals(190, at(c, t, 200, r + 10).provenance.sampleAtNanos)
	}
	@Test fun exactCutoffEligibleAndMappingCachedAcrossTicks() {
		val t = tracker(); val c = capture(); val r = receipt(t)
		val first = at(c, t, 100, r)
		assertEquals(100, first.provenance.sampleAtNanos)
		assertEquals(first, at(c, t, 200, r + 30))
		// Even an already cached sequence cannot bypass a supplied earlier cutoff.
		assertEquals(SlimeImuCaptureRejection.SAMPLE_AFTER_TICK_CUTOFF,
			assertIs<SlimeImuCaptureResult.Unavailable>(c.captureAtTick(t, 300, r - 1)).reason)
	}
	@Test fun preCutoffMappingExactAndReceiptClockNeverRead() {
		val t = tracker(); val value = at(capture(), t, 100, receipt(t) + 17)
		assertEquals(83, value.provenance.sampleAtNanos)
	}
	@Test fun unmappableAgeFailsWithoutClampingOrPoisoningCache() {
		val t = tracker(); val c = capture(); val r = receipt(t)
		assertEquals(SlimeImuCaptureRejection.SAMPLE_TIME_UNAVAILABLE,
			assertIs<SlimeImuCaptureResult.Unavailable>(c.captureAtTick(t, 100, r + 101)).reason)
		assertEquals(90, at(c, t, 100, r + 10).provenance.sampleAtNanos)
	}
	@Test fun opaqueNegativeReceiptAndOverflowFailClosed() {
		val t = tracker()
		// Set immutable sample metadata reflectively: physical production receipt uses System.nanoTime.
		val field = Tracker::class.java.declaredFields.single { it.type == Tracker.OrientationSample::class.java }.apply { isAccessible = true }
		val old = t.correctionOrientationSample()!!
		field.set(t, old.copy(receivedAtSystemNanos = -100))
		assertEquals(90, at(capture(), t, 100, -90).provenance.sampleAtNanos)
		field.set(t, old.copy(receivedAtSystemNanos = Long.MIN_VALUE))
		assertEquals(SlimeImuCaptureRejection.SAMPLE_TIME_UNAVAILABLE,
			assertIs<SlimeImuCaptureResult.Unavailable>(capture().captureAtTick(t, Long.MAX_VALUE, Long.MAX_VALUE)).reason)
	}
}
