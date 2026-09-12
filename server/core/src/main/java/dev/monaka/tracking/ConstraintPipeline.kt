package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Small orchestration layer between backend observations and effective constraints.
 * This intentionally remains detached from HumanPoseManager/IK so the Monaka
 * selection semantics can be validated before any write-back path is introduced.
 */
class ConstraintPipeline(
	private val store: ObservationStore = ObservationStore(),
	private val resolver: ConstraintResolver = ConstraintResolver(),
) {
	fun ingest(observation: PoseObservation): Boolean = store.put(observation)

	fun ingestAll(observations: Iterable<PoseObservation>): Int = store.putAll(observations)

	fun resolve(target: TrackerPosition): EffectiveConstraint =
		resolver.resolve(target, store.observationsFor(target))

	fun resolveAll(): Map<TrackerPosition, EffectiveConstraint> =
		store.targets().associateWith { resolve(it) }

	fun removeSource(sourceId: String): PoseObservation? = store.removeSource(sourceId)

	fun observations(): List<PoseObservation> = store.snapshot()

	fun clear() = store.clear()

	val observationCount: Int
		get() = store.size
}
