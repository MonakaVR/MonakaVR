package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PicoOtProducerRuntimeTests {
	private fun pose(
		trackingSpaceId: String = "space-1",
		trackerId: String = "hip",
		positionX: Float = 0.1f,
	): PicoOtProducerPoseSnapshot = PicoOtProducerPoseSnapshot(
		trackingSpaceId = trackingSpaceId,
		trackers = listOf(
			PicoOtProducerPoseSample(
				trackerId = trackerId,
				position = Vector3(positionX, 1f, 0f),
				rotation = Quaternion.IDENTITY,
			),
		),
	)

	private fun session() = PicoOtProducerSession(
		sessionId = "producer-a",
		initialTrackingSpaceId = "space-1",
		coordinateConvention = PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
	)

	private fun cadence(
		poseIntervalNanos: Long = 10L,
		batteryIntervalNanos: Long = 100L,
	) = PicoOtProducerCadence(
		PicoOtProducerCadencePolicy(
			poseIntervalNanos = poseIntervalNanos,
			batteryPollIntervalNanos = batteryIntervalNanos,
		),
	)

	@Test
	fun runtimeSeparatesHighRatePoseFromLowRateBatteryAndPreservesSequence() {
		var currentPose = pose(positionX = 0.1f)
		var batteryValue = 80
		var batteryPollCalls = 0
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = session(),
			cadence = cadence(),
			poseSource = PicoOtProducerPoseSource { currentPose },
			batterySource = PicoOtProducerBatterySource { trackerIds ->
				batteryPollCalls++
				trackerIds.associateWith { batteryValue }
			},
			sender = PicoOtFrameSender { frame ->
				sent += frame
				123
			},
		)

		assertTrue(runtime.tick(0L))
		assertEquals(1, sent.size)
		assertEquals(0L, sent[0].sequence)
		assertEquals(80, sent[0].trackers.single().batteryPercent)
		assertEquals(1, batteryPollCalls)
		assertEquals(123, runtime.lastBytesSent)

		assertFalse(runtime.tick(9L))
		assertEquals(1L, runtime.poseSnapshotsRead)

		currentPose = pose(positionX = 0.2f)
		assertTrue(runtime.tick(10L))
		assertEquals(1L, sent[1].sequence)
		assertEquals(Vector3(0.2f, 1f, 0f), sent[1].trackers.single().position)
		assertEquals(80, sent[1].trackers.single().batteryPercent)
		assertEquals(1, batteryPollCalls)

		batteryValue = 79
		assertTrue(runtime.tick(100L))
		assertEquals(2L, sent[2].sequence)
		assertEquals(79, sent[2].trackers.single().batteryPercent)
		assertEquals(2, batteryPollCalls)
		assertEquals(3L, runtime.framesSent)
		assertEquals(3L, runtime.poseSnapshotsRead)
		assertEquals(2L, runtime.batteryPolls)
	}

	@Test
	fun trackingSpaceChangeDoesNotResetProducerSequence() {
		var currentPose = pose(trackingSpaceId = "space-1")
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = session(),
			cadence = cadence(),
			poseSource = PicoOtProducerPoseSource { currentPose },
			batterySource = PicoOtProducerBatterySource { emptyMap() },
			sender = PicoOtFrameSender { frame -> sent.add(frame).let { 1 } },
		)

		runtime.tick(0L)
		currentPose = pose(trackingSpaceId = "space-2")
		runtime.tick(10L)

		assertEquals(0L, sent[0].sequence)
		assertEquals("space-1", sent[0].trackingSpaceId)
		assertEquals(1L, sent[1].sequence)
		assertEquals("space-2", sent[1].trackingSpaceId)
		assertEquals("producer-a", sent[1].sessionId)
	}

	@Test
	fun trackerDisappearanceClearsCachedBatteryBeforeReappearance() {
		var currentPose = pose()
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = session(),
			cadence = cadence(),
			poseSource = PicoOtProducerPoseSource { currentPose },
			batterySource = PicoOtProducerBatterySource { trackerIds ->
				if ("hip" in trackerIds) mapOf("hip" to 70) else emptyMap()
			},
			sender = PicoOtFrameSender { frame -> sent.add(frame).let { 1 } },
		)

		runtime.tick(0L)
		assertEquals(70, sent.last().trackers.single().batteryPercent)

		currentPose = PicoOtProducerPoseSnapshot("space-1", emptyList())
		runtime.tick(10L)
		assertTrue(runtime.batterySnapshot().isEmpty())

		currentPose = pose()
		runtime.tick(20L)
		assertNull(sent.last().trackers.single().batteryPercent)
	}

	@Test
	fun invalidBatteryResultIsRejectedWithoutAdvancingSessionOrCadence() {
		var returnInvalidBattery = true
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = session(),
			cadence = cadence(),
			poseSource = PicoOtProducerPoseSource { pose() },
			batterySource = PicoOtProducerBatterySource {
				if (returnInvalidBattery) mapOf("ghost" to 50) else mapOf("hip" to 75)
			},
			sender = PicoOtFrameSender { frame -> sent.add(frame).let { 1 } },
		)

		assertFailsWith<IllegalArgumentException> { runtime.tick(0L) }
		assertTrue(sent.isEmpty())
		returnInvalidBattery = false

		assertTrue(runtime.tick(0L))
		assertEquals(0L, sent.single().sequence)
		assertEquals(75, sent.single().trackers.single().batteryPercent)
	}

	@Test
	fun failedSendLeavesPoseDueForRetryWhileAllowingSequenceGap() {
		var failSend = true
		val sent = mutableListOf<PicoOtTransportFrame>()
		val runtime = PicoOtProducerRuntime(
			session = session(),
			cadence = cadence(),
			poseSource = PicoOtProducerPoseSource { pose() },
			batterySource = PicoOtProducerBatterySource { emptyMap() },
			sender = PicoOtFrameSender { frame ->
				if (failSend) throw IllegalStateException("synthetic send failure")
				sent += frame
				1
			},
		)

		assertFailsWith<IllegalStateException> { runtime.tick(0L) }
		assertEquals(0L, runtime.framesSent)
		failSend = false

		assertTrue(runtime.tick(0L))
		assertEquals(1L, sent.single().sequence)
		assertEquals(1L, runtime.framesSent)
	}

	@Test
	fun runtimeSendsRealUdpFrameToReceiver() {
		val loopback = InetAddress.getLoopbackAddress()
		val provider = PicoOtUdpFrameProvider(loopback, port = 0)
		val sender = PicoOtUdpFrameSender(loopback, provider.localPort)
		try {
			val runtime = PicoOtProducerRuntime(
				session = session(),
				cadence = cadence(),
				poseSource = PicoOtProducerPoseSource { pose(positionX = 0.25f) },
				batterySource = PicoOtProducerBatterySource { mapOf("hip" to 88) },
				sender = sender,
			)

			assertTrue(runtime.tick(0L))
			waitUntil { provider.acceptedFrames == 1L }

			val received = provider.latestFrame()
			assertEquals(0L, received.sequence)
			assertEquals(Vector3(0.25f, 1f, 0f), received.trackers.single().position)
			assertEquals(88, received.trackers.single().batteryPercent)
		} finally {
			sender.close()
			provider.close()
		}
	}

	private fun waitUntil(
		timeoutMillis: Long = 2_000L,
		condition: () -> Boolean,
	) {
		val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
		while (!condition()) {
			if (System.nanoTime() >= deadline) {
				fail("Timed out waiting for producer runtime condition")
			}
			Thread.sleep(10L)
		}
	}
}
