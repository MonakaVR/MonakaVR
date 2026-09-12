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
	private val freshnessPolicy: ObservationFreshnessPolicy = ObservationFreshnessPolicy(),
) {
	private val sourceProfiles = mutableMapOf<String, ObservationSourceProfile>()

	fun ingest(observation: PoseObservation): Boolean {
		val accepted = store.put(observation)
		if (accepted) sourceProfiles.remove(observation.sourceId)
		return accepted
	}

	fun ingest(
		observation: PoseObservation,
		profile: ObservationSourceProfile,
	): Boolean {
		val normalized = profile.normalize(observation)
		val accepted = store.put(normalized)
		if (accepted) sourceProfiles[normalized.sourceId] = profile
		return accepted
	}

	fun ingestAll(observations: Iterable<PoseObservation>): Int {
		var accepted = 0
		for (observation in observations) {
			if (ingest(observation)) accepted++
		}
		return accepted
	}

	fun ingestAll(
		observations: Iterable<PoseObservation>,
		profile: ObservationSourceProfile,
	): Int {
		var accepted = 0
		for (observation in observations) {
			if (ingest(observation, profile)) accepted++
		}
		return accepted
	}

	fun resolve(target: TrackerPosition): EffectiveConstraint =
		resolver.resolve(target, store.observationsFor(target))

	fun resolve(
		target: TrackerPosition,
		nowNanos: Long,
	): EffectiveConstraint = resolver.resolve(
		target,
		store.observationsFor(target).map { applyFreshness(it, nowNanos) },
	)

	fun resolveAll(): Map<TrackerPosition, EffectiveConstraint> =
		store.targets().associateWith { resolve(it) }

	fun resolveAll(nowNanos: Long): Map<TrackerPosition, EffectiveConstraint> =
		store.targets().associateWith { resolve(it, nowNanos) }

	fun removeSource(sourceId: String): PoseObservation? {
		sourceProfiles.remove(sourceId)
		return store.removeSource(sourceId)
	}

	fun observations(): List<PoseObservation> = store.snapshot()

	fun observations(nowNanos: Long): List<PoseObservation> =
		store.snapshot().map { applyFreshness(it, nowNanos) }

	fun clear() {
		store.clear()
		sourceProfiles.clear()
	}

	val observationCount: Int
		get() = store.size

	private fun applyFreshness(
		observation: PoseObservation,
		nowNanos: Long,
	): PoseObservation = (sourceProfiles[observation.sourceId]?.freshnessPolicy ?: freshnessPolicy)
		.apply(observation, nowNanos)
}
