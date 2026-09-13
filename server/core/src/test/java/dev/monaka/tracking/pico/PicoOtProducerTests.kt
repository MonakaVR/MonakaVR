package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class PicoOtProducerTests {
	@Test
	fun producerSessionOwnsSequenceAndKeepsItMonotonicAcrossTrackingSpaceChanges() {
		val session = PicoOtProducerSession(
			sessionId = "producer-a",
			initialTrackingSpaceId = "space-1",
			coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
		)

		val first = session.buildFrame(emptyList())
		assertEquals(0L, first.sequence)
		assertEquals("space-1", first.trackingSpaceId)
		assertEquals(1L, session.nextSequenceValue)

		session.updateTrackingSpace("space-2")
		val second = session.buildFrame(emptyList())
		assertEquals(1L, second.sequence)
		assertEquals("space-2", second.trackingSpaceId)
		assertEquals("producer-a", second.sessionId)

		val restarted = PicoOtProducerSession(
			sessionId = "producer-b",
			initialTrackingSpaceId = "space-3",
			coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
		)
		assertEquals(0L, restarted.buildFrame(emptyList()).sequence)
	}

	@Test
	fun producerCadenceSeparatesPoseRateFromBatteryPolling() {
		val cadence = PicoOtProducerCadence(
			PicoOtProducerCadencePolicy(
				poseIntervalNanos = 10L,
				batteryPollIntervalNanos = 100L,
			),
		)

		assertTrue(cadence.isPoseDue(0L))
		assertTrue(cadence.isBatteryPollDue(0L))

		cadence.markPoseSent(0L)
		cadence.markBatteryPolled(0L)
		assertFalse(cadence.isPoseDue(9L))
		assertTrue(cadence.isPoseDue(10L))
		assertFalse(cadence.isBatteryPollDue(99L))
		assertTrue(cadence.isBatteryPollDue(100L))

		cadence.markPoseSent(10L)
		assertFailsWith<IllegalArgumentException> { cadence.isPoseDue(9L) }
	}

	@Test
	fun poseRateFactoryKeepsBatteryCadenceCallerConfigured() {
		val policy = PicoOtProducerCadencePolicy.fromPoseRateHz(
			poseRateHz = 100,
			batteryPollIntervalMillis = 2_000L,
		)

		assertEquals(10_000_000L, policy.poseIntervalNanos)
		assertEquals(2_000_000_000L, policy.batteryPollIntervalNanos)
	}

	@Test
	fun udpSenderDeliversProducerFrameToReceiverWithoutStreamState() {
		val loopback = InetAddress.getLoopbackAddress()
		val provider = PicoOtUdpFrameProvider(loopback, port = 0)
		val sender = PicoOtUdpFrameSender(loopback, provider.localPort)
		try {
			val session = PicoOtProducerSession(
				sessionId = "producer-a",
				initialTrackingSpaceId = "space-1",
				coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
			)
			val frame = session.buildFrame(
				listOf(
					PicoOtTrackerSample(
						trackerId = "hip",
						position = Vector3(0.1f, 1f, 0.2f),
						rotation = Quaternion.IDENTITY,
						batteryPercent = 80,
					),
				),
			)

			val bytesSent = sender.send(frame)
			assertTrue(bytesSent > 0)
			waitUntil { provider.acceptedFrames == 1L }

			assertEquals(frame, provider.latestFrame())
			assertEquals(1L, sender.sentFrames)
		} finally {
			sender.close()
			provider.close()
		}
		assertTrue(sender.isClosed)
	}

	private fun waitUntil(
		timeoutMillis: Long = 2_000L,
		condition: () -> Boolean,
	) {
		val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
		while (!condition()) {
			if (System.nanoTime() >= deadline) {
				fail("Timed out waiting for producer transport condition")
			}
			Thread.sleep(10L)
		}
	}
}
