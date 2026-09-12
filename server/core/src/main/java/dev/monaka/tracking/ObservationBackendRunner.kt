package dev.monaka.tracking

/**
 * Polls registered observation backends and feeds their output into a
 * [ConstraintPipeline] using each backend's registered source profile.
 */
class ObservationBackendRunner(
	private val pipeline: ConstraintPipeline,
	backends: Iterable<ObservationBackend> = emptyList(),
) {
	private val backendsById = linkedMapOf<String, ObservationBackend>()

	init {
		for (backend in backends) {
			register(backend)
		}
	}

	fun register(backend: ObservationBackend) {
		require(backend.backendId.isNotBlank()) { "backendId must not be blank" }
		require(backend.profileId.isNotBlank()) { "profileId must not be blank" }
		require(backend.backendId !in backendsById) {
			"Observation backend '${backend.backendId}' is already registered"
		}
		backendsById[backend.backendId] = backend
	}

	fun replace(backend: ObservationBackend): ObservationBackend? {
		require(backend.backendId.isNotBlank()) { "backendId must not be blank" }
		require(backend.profileId.isNotBlank()) { "profileId must not be blank" }
		return backendsById.put(backend.backendId, backend)
	}

	fun poll(
		backendId: String,
		observedAtNanos: Long,
	): Int {
		val backend = backendsById[backendId]
			?: throw IllegalArgumentException("Unknown observation backend '$backendId'")
		return pipeline.ingestAll(backend.poll(observedAtNanos), backend.profileId)
	}

	fun pollAll(observedAtNanos: Long): Int {
		var accepted = 0
		for (backend in backendsById.values) {
			accepted += pipeline.ingestAll(backend.poll(observedAtNanos), backend.profileId)
		}
		return accepted
	}

	fun remove(backendId: String): ObservationBackend? = backendsById.remove(backendId)

	fun snapshot(): Map<String, ObservationBackend> = backendsById.toMap()

	val size: Int
		get() = backendsById.size
}
