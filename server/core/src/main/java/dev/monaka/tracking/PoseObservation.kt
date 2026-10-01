package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

data class PoseObservation(
	val sourceId: String,
	val target: TrackerPosition,
	val observedAtNanos: Long,
	val priority: Int = 0,
	val position: Vector3? = null,
	val rotation: Quaternion? = null,
	val positionQuality: ObservationQuality = if (position != null) ObservationQuality.TRACKED else ObservationQuality.UNAVAILABLE,
	val rotationQuality: ObservationQuality = if (rotation != null) ObservationQuality.TRACKED else ObservationQuality.UNAVAILABLE,
	val modality: TrackingModality = when {
		position != null && rotation != null -> TrackingModality.FULL
		rotation != null -> TrackingModality.ROTATION_ONLY
		else -> TrackingModality.NONE
	},
	val provenance: ObservationSampleProvenance? = null,
	/** Optional independent body orientation for correction learning, never used by the resolver. */
	val correctionRotation: Quaternion? = null,
) {
	init {
		require(sourceId.isNotBlank()) { "sourceId must not be blank" }
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }
		require(!positionQuality.usable || position != null) {
			"A usable position quality requires a position value"
		}
		require(!rotationQuality.usable || rotation != null) {
			"A usable rotation quality requires a rotation value"
		}
	}
}
