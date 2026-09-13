package dev.monaka.tracking.pico

/**
 * Producer cadence policy. Values are supplied by the concrete PICO utility instead
 * of hard-coding a device-specific pose rate or battery polling period in Monaka Core.
 */
data class PicoOtProducerCadencePolicy(
	val poseIntervalNanos: Long,
	val batteryPollIntervalNanos: Long,
) {
	init {
		require(poseIntervalNanos > 0L) { "poseIntervalNanos must be positive" }
		require(batteryPollIntervalNanos > 0L) { "batteryPollIntervalNanos must be positive" }
	}

	companion object {
		fun fromPoseRateHz(
			poseRateHz: Int,
			batteryPollIntervalMillis: Long,
		): PicoOtProducerCadencePolicy {
			require(poseRateHz > 0) { "poseRateHz must be positive" }
			require(batteryPollIntervalMillis > 0L) {
				"batteryPollIntervalMillis must be positive"
			}
			return PicoOtProducerCadencePolicy(
				poseIntervalNanos = 1_000_000_000L / poseRateHz,
				batteryPollIntervalNanos = batteryPollIntervalMillis * 1_000_000L,
			)
		}
	}
}

/**
 * Monotonic producer scheduler state. The first pose and battery poll are due
 * immediately; subsequent operations become due only after their configured interval.
 */
class PicoOtProducerCadence(
	private val policy: PicoOtProducerCadencePolicy,
) {
	private var lastPoseSentAtNanos: Long? = null
	private var lastBatteryPolledAtNanos: Long? = null

	fun isPoseDue(nowNanos: Long): Boolean = isDue(
		nowNanos,
		lastPoseSentAtNanos,
		policy.poseIntervalNanos,
	)

	fun markPoseSent(nowNanos: Long) {
		validateMonotonic(nowNanos, lastPoseSentAtNanos, "pose")
		lastPoseSentAtNanos = nowNanos
	}

	fun isBatteryPollDue(nowNanos: Long): Boolean = isDue(
		nowNanos,
		lastBatteryPolledAtNanos,
		policy.batteryPollIntervalNanos,
	)

	fun markBatteryPolled(nowNanos: Long) {
		validateMonotonic(nowNanos, lastBatteryPolledAtNanos, "battery")
		lastBatteryPolledAtNanos = nowNanos
	}

	private fun isDue(
		nowNanos: Long,
		lastAtNanos: Long?,
		intervalNanos: Long,
	): Boolean {
		require(nowNanos >= 0L) { "nowNanos must be non-negative" }
		if (lastAtNanos == null) return true
		require(nowNanos >= lastAtNanos) { "producer monotonic time moved backwards" }
		return nowNanos - lastAtNanos >= intervalNanos
	}

	private fun validateMonotonic(
		nowNanos: Long,
		lastAtNanos: Long?,
		name: String,
	) {
		require(nowNanos >= 0L) { "nowNanos must be non-negative" }
		require(lastAtNanos == null || nowNanos >= lastAtNanos) {
			"$name monotonic time moved backwards"
		}
	}
}
