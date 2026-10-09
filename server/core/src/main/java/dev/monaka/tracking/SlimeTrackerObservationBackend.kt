package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker

/**
 * Observation backend that mirrors the current set of legacy SlimeVR trackers.
 *
 * The tracker provider is evaluated on every poll so tracker add/remove and body
 * assignment changes are reflected without rebuilding the backend instance.
 * Each poll is an authoritative snapshot of currently assigned trackers.
 */
class SlimeTrackerObservationBackend(
	override val backendId: String,
	override val profileId: String,
	private val trackersProvider: () -> Iterable<Tracker>,
	sourcePrefix: String = backendId,
	private val assignments: () -> Map<dev.slimevr.tracking.trackers.TrackerPosition, MainTrackerAssignment> = { emptyMap() },
) : ObservationBackend {
	override val sourceSetMode: ObservationSourceSetMode = ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT
	private val adapter = SlimeTrackerPoseObservationAdapter(sourcePrefix = sourcePrefix)

	init {
		require(backendId.isNotBlank()) { "backendId must not be blank" }
		require(profileId.isNotBlank()) { "profileId must not be blank" }
	}

	override fun poll(observedAtNanos: Long): List<PoseObservation> {
		val diagnostic = dev.monaka.tracking.diagnostic.RuntimeDiagnostics.observer
		val observations = if (diagnostic != null) mutableListOf<Triple<Tracker, PoseObservation?, Boolean>>() else null
		val result = trackersProvider().mapNotNull { tracker ->
			val id = "slime:${tracker.name}"
			val target = assignments().entries.firstOrNull { (_, relation) ->
				relation.mainTracker.observationId == id || relation.rotationFallbackTracker?.observationId == id
			}?.key
			adapter.adapt(tracker, observedAtNanos, target).also { observations?.add(Triple(tracker, it, target != null)) }
		}
		if (observations != null) diagnostic?.slimeSnapshot(observations)
		return result
	}
}
