package dev.monaka.tracking.pico

/**
 * Coarse health state for the PICO OT pose stream.
 *
 * UDP has no connection event, so health is derived from the age of the most recent
 * newly accepted transport frame. Duplicate, stale, retired-session, or malformed
 * datagrams do not refresh this state because they do not provide a new usable pose.
 */
enum class PicoOtTransportConnectionState {
	WAITING,
	CONNECTED,
	STALE,
	DISCONNECTED,
}

/**
 * Time thresholds used to classify PICO OT stream health.
 *
 * A frame remains CONNECTED through [staleAfterNanos], becomes STALE after that,
 * and becomes DISCONNECTED only after [disconnectedAfterNanos]. Concrete values are
 * deliberately supplied by the caller rather than hard-coded into Monaka Core.
 */
data class PicoOtTransportConnectionPolicy(
	val staleAfterNanos: Long,
	val disconnectedAfterNanos: Long,
) {
	init {
		require(staleAfterNanos > 0L) { "staleAfterNanos must be positive" }
		require(disconnectedAfterNanos > staleAfterNanos) {
			"disconnectedAfterNanos must be greater than staleAfterNanos"
		}
	}
}

/**
 * Thread-safe enough for one receiver thread plus arbitrary diagnostic/UI readers.
 * Timestamps are expected to come from the same monotonic clock domain.
 */
class PicoOtTransportConnectionMonitor(
	val policy: PicoOtTransportConnectionPolicy,
) {
	@Volatile
	private var acceptedFrameAtNanos: Long? = null

	val lastAcceptedFrameAtNanos: Long?
		get() = acceptedFrameAtNanos

	fun markAcceptedFrame(nowNanos: Long) {
		acceptedFrameAtNanos = nowNanos
	}

	fun ageNanos(nowNanos: Long): Long? {
		val acceptedAt = acceptedFrameAtNanos ?: return null
		return (nowNanos - acceptedAt).coerceAtLeast(0L)
	}

	fun state(nowNanos: Long): PicoOtTransportConnectionState {
		val age = ageNanos(nowNanos) ?: return PicoOtTransportConnectionState.WAITING
		return when {
			age <= policy.staleAfterNanos -> PicoOtTransportConnectionState.CONNECTED
			age <= policy.disconnectedAfterNanos -> PicoOtTransportConnectionState.STALE
			else -> PicoOtTransportConnectionState.DISCONNECTED
		}
	}
}
