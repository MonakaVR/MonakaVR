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
) : ObservationBackend, AssignmentSnapshotAwareObservationBackend {
	override val sourceSetMode: ObservationSourceSetMode = ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT
	private val adapter = SlimeTrackerPoseObservationAdapter(sourcePrefix = sourcePrefix)

	init {
		require(backendId.isNotBlank()) { "backendId must not be blank" }
		require(profileId.isNotBlank()) { "profileId must not be blank" }
	}

	override fun poll(observedAtNanos: Long): List<PoseObservation> = pollTargets(observedAtNanos, assignments())

	override fun poll(observedAtNanos: Long, assignment: TrackerBodyAssignments.Snapshot): List<PoseObservation> =
		pollTargets(observedAtNanos, assignment.targets)

	private fun pollTargets(
		observedAtNanos: Long,
		targets: Map<dev.slimevr.tracking.trackers.TrackerPosition, MainTrackerAssignment>,
	): List<PoseObservation> =
		trackersProvider().mapNotNull { tracker ->
			val id = "slime:${tracker.name}"
			val target = targets.entries.firstOrNull { (_, relation) ->
				relation.mainTracker.observationId == id || relation.rotationFallbackTracker?.observationId == id
			}?.key
			adapter.adapt(tracker, observedAtNanos, target)
		}
}
