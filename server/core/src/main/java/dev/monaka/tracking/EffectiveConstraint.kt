package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

data class ResolvedComponent<T>(
	val value: T,
	val sourceId: String,
	val quality: ObservationQuality,
	val observedAtNanos: Long,
)

data class EffectiveConstraint(
	val target: TrackerPosition,
	val position: ResolvedComponent<Vector3>? = null,
	val rotation: ResolvedComponent<Quaternion>? = null,
)
