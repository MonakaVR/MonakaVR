package dev.monaka.tracking

/**
 * Applies component-specific age limits to observations without discarding their
 * last known values. Expired usable components become STALE so the resolver can
 * fall back to another source while diagnostics can still inspect the old pose.
 */
data class ObservationFreshnessPolicy(
	val positionTimeoutNanos: Long = Long.MAX_VALUE,
	val rotationTimeoutNanos: Long = Long.MAX_VALUE,
) {
	init {
		require(positionTimeoutNanos >= 0L) { "positionTimeoutNanos must be non-negative" }
		require(rotationTimeoutNanos >= 0L) { "rotationTimeoutNanos must be non-negative" }
	}

	fun apply(
		observation: PoseObservation,
		nowNanos: Long,
	): PoseObservation {
		require(nowNanos >= 0L) { "nowNanos must be non-negative" }

		val ageNanos = if (nowNanos <= observation.observedAtNanos) {
			0L
		} else {
			nowNanos - observation.observedAtNanos
		}

		return observation.copy(
			positionQuality = expireIfNeeded(
				observation.positionQuality,
				ageNanos,
				positionTimeoutNanos,
			),
			rotationQuality = expireIfNeeded(
				observation.rotationQuality,
				ageNanos,
				rotationTimeoutNanos,
			),
		)
	}

	private fun expireIfNeeded(
		quality: ObservationQuality,
		ageNanos: Long,
		timeoutNanos: Long,
	): ObservationQuality {
		if (!quality.usable) return quality
		return if (ageNanos > timeoutNanos) ObservationQuality.STALE else quality
	}
}
