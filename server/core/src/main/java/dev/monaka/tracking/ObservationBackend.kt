package dev.monaka.tracking

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

	fun poll(observedAtNanos: Long): List<PoseObservation>
}
