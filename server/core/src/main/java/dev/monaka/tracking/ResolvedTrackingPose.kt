package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Main sample diagnostics are separate from the components selected by the resolver. */
data class MainSampleState(
	val modality: TrackingModality,
	val positionValid: Boolean,
	val rotationValid: Boolean,
) {
	companion object {
		fun from(observation: PoseObservation?) = MainSampleState(
			observation?.modality ?: TrackingModality.NONE,
			observation?.position != null && observation.positionQuality.usable,
			observation?.rotation != null && observation.rotationQuality.usable,
		)
	}
}

/** Backend-neutral selection result. This conversion makes no eligibility/ownership decisions. */
data class ResolvedTrackingPose(
	val target: TrackerPosition,
	val space: CoordinateSpace,
	val position: ResolvedComponent<Vector3>?,
	val rotation: ResolvedComponent<Quaternion>?,
	val main: MainSampleState,
) {
	val positionValid get() = position != null
	val rotationValid get() = rotation != null
	val positionOwner get() = position?.sourceId
	val rotationOwner get() = rotation?.sourceId
	val modality get() = poseModality(positionValid, rotationValid)
	fun ikConstraint() = EffectiveConstraint(target, position, rotation)
	fun directOutput() = OutputPose(target, space, position, rotation)
	companion object {
		fun from(constraint: EffectiveConstraint, space: CoordinateSpace, main: PoseObservation?) =
			ResolvedTrackingPose(constraint.target, space, constraint.position, constraint.rotation, MainSampleState.from(main))
	}
}

enum class OutputPositionSource { NONE, RESOLVED_MAIN, BACKGROUND_IK, CONVERGENCE }

/** Visible sample, not solver input. Null components are unavailable, never a zero/held pose. */
data class OutputPose(
	val target: TrackerPosition,
	val space: CoordinateSpace,
	val position: ResolvedComponent<Vector3>? = null,
	val rotation: ResolvedComponent<Quaternion>? = null,
	val positionSource: OutputPositionSource = if (position == null) OutputPositionSource.NONE else OutputPositionSource.RESOLVED_MAIN,
) {
	val positionValid get() = position != null
	val rotationValid get() = rotation != null
	val positionOwner get() = position?.sourceId
	val rotationOwner get() = rotation?.sourceId
	val modality get() = poseModality(positionValid, rotationValid)
	init { require((position == null) == (positionSource == OutputPositionSource.NONE)) }
}

private fun poseModality(position: Boolean, rotation: Boolean) = when {
	position && rotation -> TrackingModality.FULL
	rotation -> TrackingModality.ROTATION_ONLY
	else -> TrackingModality.NONE
}
