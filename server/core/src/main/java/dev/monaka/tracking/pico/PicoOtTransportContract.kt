package dev.monaka.tracking.pico

const val PICO_OT_TRANSPORT_PROTOCOL_VERSION: Int = 1

/**
 * Logical transport frame exchanged between the PICO Utility/driver side and
 * Monaka Core before choosing a concrete wire format such as JSON or IPC.
 *
 * [sessionId] identifies one producer lifetime. [sequence] must be monotonic
 * within that session and allows Monaka to reject out-of-order frames without
 * depending on a remote clock. [trackingSpaceId] identifies the coordinate frame
 * used by the poses and should change whenever the producer performs a discontinuous
 * tracking-origin reset/recenter.
 *
 * [coordinateConvention] makes the pose basis explicit at the transport boundary.
 * The data-source bridge converts it into Monaka's canonical coordinate convention
 * before producing [PicoOtSnapshot].
 *
 * [trackers] is an authoritative set. A tracker omitted from a newer frame is no
 * longer present. [PicoOtTrackerSample.batteryPercent] may be omitted on high-rate
 * pose frames; the receiver preserves the last known battery value while the tracker
 * remains present in the same session.
 */
data class PicoOtTransportFrame(
	val protocolVersion: Int = PICO_OT_TRANSPORT_PROTOCOL_VERSION,
	val coordinateConvention: PicoOtCoordinateConvention =
		PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
	val sessionId: String,
	val trackingSpaceId: String,
	val sequence: Long,
	val trackers: List<PicoOtTrackerSample>,
) {
	init {
		require(protocolVersion > 0) { "protocolVersion must be positive" }
		require(sessionId.isNotBlank()) { "sessionId must not be blank" }
		require(trackingSpaceId.isNotBlank()) { "trackingSpaceId must not be blank" }
		require(sequence >= 0L) { "sequence must be non-negative" }
		val ids = trackers.map { it.trackerId }
		require(ids.size == ids.toSet().size) { "PICO OT transport frame contains duplicate tracker ids" }
	}

	val trackerCount: Int
		get() = trackers.size
}

/**
 * Supplies the latest complete transport frame. Concrete implementations may read
 * from shared memory, a socket, a local HTTP endpoint, or another IPC mechanism.
 */
fun interface PicoOtTransportFrameProvider {
	fun latestFrame(): PicoOtTransportFrame
}
