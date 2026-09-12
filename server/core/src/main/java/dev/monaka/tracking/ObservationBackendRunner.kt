package dev.monaka.tracking

/**
 * Polls registered observation backends and feeds their output into a
 * [ConstraintPipeline] using each backend's registered source profile.
 *
 * The runner also owns lifecycle bookkeeping for sources emitted by each backend.
 * Authoritative snapshot backends remove disappeared sources immediately, while
 * incremental backends leave omitted sources in the pipeline to age out normally.
 */
class ObservationBackendRunner(
	private val pipeline: ConstraintPipeline,
	backends: Iterable<ObservationBackend> = emptyList(),
) {
	private val backendsById = linkedMapOf<String, ObservationBackend>()
	private val sourcesByBackend = linkedMapOf<String, MutableSet<String>>()
	private val sourceOwnerById = mutableMapOf<String, String>()

	init {
		for (backend in backends) {
			register(backend)
		}
	}

	fun register(backend: ObservationBackend) {
		validateBackend(backend)
		require(backend.backendId !in backendsById) {
			"Observation backend '${backend.backendId}' is already registered"
		}
		backendsById[backend.backendId] = backend
		sourcesByBackend.putIfAbsent(backend.backendId, linkedSetOf())
	}

	fun replace(backend: ObservationBackend): ObservationBackend? {
		validateBackend(backend)
		val previous = backendsById[backend.backendId]
		if (previous != null) {
			releaseBackendSources(backend.backendId)
		}
		backendsById[backend.backendId] = backend
		sourcesByBackend.putIfAbsent(backend.backendId, linkedSetOf())
		return previous
	}

	fun poll(
		backendId: String,
		observedAtNanos: Long,
	): Int {
		val backend = backendsById[backendId]
			?: throw IllegalArgumentException("Unknown observation backend '$backendId'")
		return pollBackend(backend, observedAtNanos)
	}

	fun pollAll(observedAtNanos: Long): Int {
		var accepted = 0
		for (backend in backendsById.values) {
			accepted += pollBackend(backend, observedAtNanos)
		}
		return accepted
	}

	fun remove(backendId: String): ObservationBackend? {
		val removed = backendsById.remove(backendId) ?: return null
		releaseBackendSources(backendId)
		sourcesByBackend.remove(backendId)
		return removed
	}

	fun clear() {
		for (backendId in backendsById.keys.toList()) {
			releaseBackendSources(backendId)
		}
		backendsById.clear()
		sourcesByBackend.clear()
		sourceOwnerById.clear()
	}

	fun snapshot(): Map<String, ObservationBackend> = backendsById.toMap()

	fun ownedSources(backendId: String): Set<String> = sourcesByBackend[backendId]?.toSet() ?: emptySet()

	val size: Int
		get() = backendsById.size

	private fun pollBackend(
		backend: ObservationBackend,
		observedAtNanos: Long,
	): Int {
		val observations = backend.poll(observedAtNanos)
		val observedSourceIds = linkedSetOf<String>()

		for (observation in observations) {
			require(observedSourceIds.add(observation.sourceId)) {
				"Observation backend '${backend.backendId}' emitted duplicate sourceId '${observation.sourceId}' in one poll"
			}
			val owner = sourceOwnerById[observation.sourceId]
			require(owner == null || owner == backend.backendId) {
				"Observation source '${observation.sourceId}' is already owned by backend '$owner'"
			}
		}

		val previouslyOwned = sourcesByBackend.getOrPut(backend.backendId) { linkedSetOf() }.toSet()
		val accepted = pipeline.ingestAll(observations, backend.profileId)

		val owned = sourcesByBackend.getValue(backend.backendId)
		for (sourceId in observedSourceIds) {
			sourceOwnerById[sourceId] = backend.backendId
			owned += sourceId
		}

		if (backend.sourceSetMode == ObservationSourceSetMode.AUTHORITATIVE_SNAPSHOT) {
			for (sourceId in previouslyOwned - observedSourceIds) {
				releaseSource(backend.backendId, sourceId)
			}
		}

		return accepted
	}

	private fun validateBackend(backend: ObservationBackend) {
		require(backend.backendId.isNotBlank()) { "backendId must not be blank" }
		require(backend.profileId.isNotBlank()) { "profileId must not be blank" }
	}

	private fun releaseBackendSources(backendId: String) {
		val owned = sourcesByBackend[backendId]?.toList() ?: return
		for (sourceId in owned) {
			releaseSource(backendId, sourceId)
		}
	}

	private fun releaseSource(
		backendId: String,
		sourceId: String,
	) {
		if (sourceOwnerById[sourceId] != backendId) return
		pipeline.removeSource(sourceId)
		sourceOwnerById.remove(sourceId)
		sourcesByBackend[backendId]?.remove(sourceId)
	}
}
