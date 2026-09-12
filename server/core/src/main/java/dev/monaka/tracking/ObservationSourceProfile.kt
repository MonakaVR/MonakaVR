package dev.monaka.tracking

/**
 * Declares backend/source policy independently from a concrete tracker instance.
 *
 * Profiles centralize component capability, resolver priority, and freshness limits
 * so fallback behavior can be configured without hard-coding backend names into the
 * resolver itself.
 */
data class ObservationComponentPolicy(
	val supported: Boolean,
	val timeoutNanos: Long = Long.MAX_VALUE,
) {
	init {
		require(timeoutNanos >= 0L) { "timeoutNanos must be non-negative" }
	}
}

data class ObservationSourceProfile(
	val profileId: String,
	val priority: Int,
	val position: ObservationComponentPolicy,
	val rotation: ObservationComponentPolicy,
) {
	init {
		require(profileId.isNotBlank()) { "profileId must not be blank" }
	}

	fun normalize(observation: PoseObservation): PoseObservation = observation.copy(
		priority = priority,
		position = if (position.supported) observation.position else null,
		rotation = if (rotation.supported) observation.rotation else null,
		positionQuality = when {
			!position.supported -> ObservationQuality.UNAVAILABLE
			observation.position == null -> ObservationQuality.UNAVAILABLE
			else -> observation.positionQuality
		},
		rotationQuality = when {
			!rotation.supported -> ObservationQuality.UNAVAILABLE
			observation.rotation == null -> ObservationQuality.UNAVAILABLE
			else -> observation.rotationQuality
		},
	)

	val freshnessPolicy: ObservationFreshnessPolicy
		get() = ObservationFreshnessPolicy(
			positionTimeoutNanos = if (position.supported) position.timeoutNanos else Long.MAX_VALUE,
			rotationTimeoutNanos = if (rotation.supported) rotation.timeoutNanos else Long.MAX_VALUE,
		)

	companion object {
		fun sixDof(
			profileId: String,
			priority: Int,
			positionTimeoutNanos: Long,
			rotationTimeoutNanos: Long,
		): ObservationSourceProfile = ObservationSourceProfile(
			profileId = profileId,
			priority = priority,
			position = ObservationComponentPolicy(true, positionTimeoutNanos),
			rotation = ObservationComponentPolicy(true, rotationTimeoutNanos),
		)

		fun rotationOnly(
			profileId: String,
			priority: Int,
			rotationTimeoutNanos: Long,
		): ObservationSourceProfile = ObservationSourceProfile(
			profileId = profileId,
			priority = priority,
			position = ObservationComponentPolicy(false),
			rotation = ObservationComponentPolicy(true, rotationTimeoutNanos),
		)
	}
}
