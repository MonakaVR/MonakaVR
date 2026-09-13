package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PicoMotionTrackerBridgeReceiverProviderTests {
	private fun receiverTracker(
		serial: String = "PICO-SERIAL-001",
		senderSessionId: Long = 77L,
		lastPoseSequence: Long = 12L,
		connectedSnapshot: Boolean = true,
		position: Vector3? = Vector3(0.1f, 1f, 0.2f),
		rotation: Quaternion? = Quaternion.IDENTITY,
		positionValid: Boolean = true,
		orientationSamplePresent: Boolean = true,
		lastPoseReceivePcMonotonicNanos: Long = 123L,
		mappedPosePcMonotonicNanos: Long? = 120L,
	) = PicoMotionTrackerBridgeReceiverTracker(
		serial = serial,
		senderSessionId = senderSessionId,
		lastPoseSequence = lastPoseSequence,
		connectedSnapshot = connectedSnapshot,
		positionMeters = position,
		orientation = rotation,
		positionValid = positionValid,
		orientationSamplePresent = orientationSamplePresent,
		lastPoseReceivePcMonotonicNanos = lastPoseReceivePcMonotonicNanos,
		mappedPosePcMonotonicNanos = mappedPosePcMonotonicNanos,
	)

	@Test
	fun providerPumpsReceiverBeforeTakingSnapshotAndPreservesState() {
		val calls = mutableListOf<String>()
		val receiver = object : PicoMotionTrackerBridgeReceiverClient {
			override fun pump() {
				calls += "pump"
			}

			override fun readSnapshot(): PicoMotionTrackerBridgeReceiverSnapshot {
				calls += "snapshot"
				return PicoMotionTrackerBridgeReceiverSnapshot(
					activeSenderSessionId = 77L,
					trackers = listOf(
						receiverTracker(
							connectedSnapshot = false,
							positionValid = false,
							orientationSamplePresent = true,
							lastPoseReceivePcMonotonicNanos = 456L,
							mappedPosePcMonotonicNanos = 450L,
						),
					),
				)
			}
		}

		val state = PicoMotionTrackerBridgeReceiverStateProvider(receiver).snapshot().single()

		assertEquals(listOf("pump", "snapshot"), calls)
		assertEquals("PICO-SERIAL-001", state.serial)
		assertEquals(false, state.connectedSnapshot)
		assertEquals(false, state.positionValid)
		assertEquals(true, state.orientationSamplePresent)
		assertEquals(Vector3(0.1f, 1f, 0.2f), state.positionMeters)
		assertEquals(Quaternion.IDENTITY, state.orientation)
		assertEquals(456L, state.lastPoseReceivePcMonotonicNanos)
		assertEquals(450L, state.mappedPosePcMonotonicNanos)
		assertEquals(456L, state.observationPcMonotonicNanos)
	}

	@Test
	fun noActiveSenderProducesAuthoritativeEmptyStateSet() {
		val receiver = object : PicoMotionTrackerBridgeReceiverClient {
			override fun pump() = Unit
			override fun readSnapshot() = PicoMotionTrackerBridgeReceiverSnapshot()
		}

		assertEquals(emptyList(), PicoMotionTrackerBridgeReceiverStateProvider(receiver).snapshot())
	}

	@Test
	fun receiverSnapshotRejectsMixedSessionsAndDuplicateSerials() {
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeReceiverSnapshot(
				activeSenderSessionId = 77L,
				trackers = listOf(receiverTracker(senderSessionId = 88L)),
			)
		}

		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeReceiverSnapshot(
				activeSenderSessionId = 77L,
				trackers = listOf(receiverTracker(), receiverTracker(lastPoseSequence = 13L)),
			)
		}
	}

	@Test
	fun receiverSnapshotWithoutSenderCannotRetainOldTrackerState() {
		assertFailsWith<IllegalArgumentException> {
			PicoMotionTrackerBridgeReceiverSnapshot(
				activeSenderSessionId = null,
				trackers = listOf(receiverTracker()),
			)
		}
	}

	@Test
	fun receiverTrackerValidatesTransportMetadataAndComponentSamples() {
		assertFailsWith<IllegalArgumentException> {
			receiverTracker(senderSessionId = 0L)
		}
		assertFailsWith<IllegalArgumentException> {
			receiverTracker(lastPoseSequence = -1L)
		}
		assertFailsWith<IllegalArgumentException> {
			receiverTracker(lastPoseSequence = 0x1_0000_0000L)
		}
		assertFailsWith<IllegalArgumentException> {
			receiverTracker(positionValid = true, position = null)
		}
		assertFailsWith<IllegalArgumentException> {
			receiverTracker(orientationSamplePresent = true, rotation = null)
		}
	}

	@Test
	fun pumpFailurePropagatesInsteadOfPublishingLastSnapshotAsFresh() {
		val receiver = object : PicoMotionTrackerBridgeReceiverClient {
			override fun pump() {
				throw IllegalStateException("receiver failed")
			}

			override fun readSnapshot(): PicoMotionTrackerBridgeReceiverSnapshot {
				throw AssertionError("snapshot must not be read after pump failure")
			}
		}

		val exception = assertFailsWith<IllegalStateException> {
			PicoMotionTrackerBridgeReceiverStateProvider(receiver).snapshot()
		}
		assertEquals("receiver failed", exception.message)
	}

	@Test
	fun mappedTimestampRemainsMetadataNotObservationAge() {
		val tracker = receiverTracker(
			lastPoseReceivePcMonotonicNanos = 1_000L,
			mappedPosePcMonotonicNanos = 9_000L,
		)
		val receiver = object : PicoMotionTrackerBridgeReceiverClient {
			override fun pump() = Unit
			override fun readSnapshot() = PicoMotionTrackerBridgeReceiverSnapshot(
				activeSenderSessionId = 77L,
				trackers = listOf(tracker),
			)
		}

		val state = PicoMotionTrackerBridgeReceiverStateProvider(receiver).snapshot().single()
		assertEquals(1_000L, state.observationPcMonotonicNanos)
		assertEquals(9_000L, state.mappedPosePcMonotonicNanos)
		assertNull(null)
	}
}
