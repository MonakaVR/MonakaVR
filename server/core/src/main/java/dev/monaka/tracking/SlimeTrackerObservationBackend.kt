package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker

/**
 * Observation backend that mirrors the current set of legacy SlimeVR trackers.
 *
 * The tracker provider is evaluated on every poll so tracker add/remove and body
 * assignment changes are reflected without rebuilding the backend instance.
 */
class SlimeTrackerObservationBackend(
	override val backendId: String,
	override val profileId: String,
	private val trackersProvider: () -> Iterable<Tracker>,
	sourcePrefix: String = backendId,
) : ObservationBackend {
	private val adapter = SlimeTrackerPoseObservationAdapter(sourcePrefix = sourcePrefix)

	init {
		require(backendId.isNotBlank()) { "backendId must not be blank" }
		require(profileId.isNotBlank()) { "profileId must not be blank" }
	}

	override fun poll(observedAtNanos: Long): List<PoseObservation> =
		trackersProvider().mapNotNull { adapter.adapt(it, observedAtNanos) }
}
