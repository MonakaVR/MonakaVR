package dev.monaka.tracking.pico

/**
 * Producer-side session state for PICO OT transport frames.
 *
 * A producer lifetime owns one stable [sessionId]. Sequence numbers are monotonic
 * within that lifetime and intentionally do not reset when the tracking space changes.
 * Restarting the producer should create a new instance with a new session id, which
 * resets the sequence to zero for the new session.
 */
class PicoOtProducerSession(
	val sessionId: String,
	initialTrackingSpaceId: String,
	val coordinateConvention: PicoOtCoordinateConvention,
) {
	var trackingSpaceId: String = initialTrackingSpaceId
		private set

	private var nextSequence: Long = 0L

	init {
		require(sessionId.isNotBlank()) { "sessionId must not be blank" }
		require(initialTrackingSpaceId.isNotBlank()) { "initialTrackingSpaceId must not be blank" }
	}

	val nextSequenceValue: Long
		get() = nextSequence

	fun updateTrackingSpace(trackingSpaceId: String) {
		require(trackingSpaceId.isNotBlank()) { "trackingSpaceId must not be blank" }
		this.trackingSpaceId = trackingSpaceId
	}

	fun buildFrame(trackers: List<PicoOtTrackerSample>): PicoOtTransportFrame {
		check(nextSequence < Long.MAX_VALUE) {
			"PICO OT producer sequence exhausted; start a new producer session"
		}

		val frame = PicoOtTransportFrame(
			coordinateConvention = coordinateConvention,
			sessionId = sessionId,
			trackingSpaceId = trackingSpaceId,
			sequence = nextSequence,
			trackers = trackers,
		)
		nextSequence++
		return frame
	}
}
