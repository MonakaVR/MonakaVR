package dev.monaka.tracking

/**
 * Describes how a backend's source set should be interpreted across polls.
 */
enum class ObservationSourceSetMode {
	/**
	 * A poll contains only updates. Sources omitted from a poll remain owned by the
	 * backend and age out through freshness policy unless the backend is removed.
	 */
	INCREMENTAL,

	/**
	 * A poll is a complete snapshot of currently active sources. Previously owned
	 * sources omitted from a poll are removed from the pipeline immediately.
	 */
	AUTHORITATIVE_SNAPSHOT,
}

/**
 * Backend-neutral producer of pose observations.
 *
 * Concrete backends expose observations plus the policy profile that should be
 * applied when those observations enter the constraint pipeline. The backend does
 * not resolve constraints or write to the skeleton directly.
 */
interface ObservationBackend {
	val backendId: String
	val profileId: String
	val sourceSetMode: ObservationSourceSetMode
		get() = ObservationSourceSetMode.INCREMENTAL

	fun poll(observedAtNanos: Long): List<PoseObservation>

	/** Explicit incremental removals, consumed after poll and before its updates. */
	fun drainRemovedSources(): Set<String> = emptySet()
}
