package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

/**
 * Small orchestration layer between backend observations and effective constraints.
 * Selection remains independent of the solver. MonakaRuntime owns the pipeline;
 * ConstraintIkWriteback supplies its resolved components to the existing IK inputs.
 */
class ConstraintPipeline(
	private val store: ObservationStore = ObservationStore(),
	private val resolver: ConstraintResolver = ConstraintResolver(),
	private val freshnessPolicy: ObservationFreshnessPolicy = ObservationFreshnessPolicy(),
	private val profileRegistry: ObservationSourceProfileRegistry = ObservationSourceProfileRegistry(),
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

	fun ingest(
		observation: PoseObservation,
		profileId: String,
	): Boolean = ingest(observation, profileRegistry.require(profileId))

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

	fun ingestAll(
		observations: Iterable<PoseObservation>,
		profileId: String,
	): Int {
		val profile = profileRegistry.require(profileId)
		return ingestAll(observations, profile)
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
