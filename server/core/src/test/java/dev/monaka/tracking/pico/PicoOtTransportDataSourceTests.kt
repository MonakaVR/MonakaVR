package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PicoOtTransportDataSourceTests {
	@Test
	fun mapsTransportFrameAndExposesSessionMetadata() {
		val frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 7L,
			trackers = listOf(
				PicoOtTrackerSample(
					trackerId = "tracker-1",
					position = Vector3(0.1f, 1f, 0.2f),
					rotation = Quaternion.IDENTITY,
					batteryPercent = 81,
				),
			),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { frame })

		val snapshot = dataSource.snapshot(100L)

		assertEquals(1, snapshot.trackers.size)
		assertEquals("tracker-1", snapshot.trackers.single().trackerId)
		assertEquals(81, snapshot.trackers.single().batteryPercent)
		assertEquals("session-a", dataSource.sessionId)
		assertEquals("space-1", dataSource.trackingSpaceId)
		assertEquals(7L, dataSource.sequence)
		assertEquals(1, dataSource.trackerCount)
		assertEquals(1, frame.trackerCount)
	}

	@Test
	fun sparseBatteryUpdateIsRetainedOnlyWhileTrackerRemainsPresent() {
		var frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 1L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1", batteryPercent = 65)),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { frame })

		assertEquals(65, dataSource.snapshot(1L).trackers.single().batteryPercent)

		frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 2L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1")),
		)
		assertEquals(65, dataSource.snapshot(2L).trackers.single().batteryPercent)

		frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 3L,
			trackers = emptyList(),
		)
		assertEquals(0, dataSource.snapshot(3L).trackers.size)

		frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 4L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1")),
		)
		assertNull(dataSource.snapshot(4L).trackers.single().batteryPercent)
	}

	@Test
	fun duplicateFrameIsIdempotentButOlderOrMutatedSequenceIsRejected() {
		var frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 10L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1", batteryPercent = 50)),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { frame })
		val first = dataSource.snapshot(10L)

		assertEquals(first, dataSource.snapshot(11L))

		frame = frame.copy(trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1", batteryPercent = 51)))
		assertFailsWith<IllegalArgumentException> { dataSource.snapshot(12L) }

		frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 9L,
			trackers = emptyList(),
		)
		assertFailsWith<IllegalArgumentException> { dataSource.snapshot(13L) }
	}

	@Test
	fun producerSessionChangeResetsSequenceAndSparseBatteryState() {
		var frame = PicoOtTransportFrame(
			sessionId = "session-a",
			trackingSpaceId = "space-1",
			sequence = 100L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1", batteryPercent = 77)),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { frame })
		dataSource.snapshot(1L)

		frame = PicoOtTransportFrame(
			sessionId = "session-b",
			trackingSpaceId = "space-2",
			sequence = 0L,
			trackers = listOf(PicoOtTrackerSample(trackerId = "tracker-1")),
		)
		val restarted = dataSource.snapshot(2L)

		assertEquals("session-b", dataSource.sessionId)
		assertEquals("space-2", dataSource.trackingSpaceId)
		assertEquals(0L, dataSource.sequence)
		assertNull(restarted.trackers.single().batteryPercent)
	}

	@Test
	fun rejectsUnsupportedProtocolAndMalformedTransportMetadata() {
		assertFailsWith<IllegalArgumentException> {
			PicoOtTransportFrame(
				sessionId = "",
				trackingSpaceId = "space",
				sequence = 0L,
				trackers = emptyList(),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			PicoOtTransportFrame(
				sessionId = "session",
				trackingSpaceId = "space",
				sequence = -1L,
				trackers = emptyList(),
			)
		}

		val unsupported = PicoOtTransportFrame(
			protocolVersion = PICO_OT_TRANSPORT_PROTOCOL_VERSION + 1,
			sessionId = "session",
			trackingSpaceId = "space",
			sequence = 0L,
			trackers = emptyList(),
		)
		val dataSource = PicoOtTransportDataSource(PicoOtTransportFrameProvider { unsupported })

		assertFailsWith<IllegalArgumentException> { dataSource.snapshot(0L) }
	}
}
