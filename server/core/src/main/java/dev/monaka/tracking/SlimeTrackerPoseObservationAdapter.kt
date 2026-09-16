package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus

/**
 * Read-only adapter that mirrors an existing SlimeVR [Tracker] into Monaka's
 * backend-neutral [PoseObservation] model. It does not modify the tracker or the
 * existing HumanPoseManager/IK path.
 */
class SlimeTrackerPoseObservationAdapter(
	private val sourcePrefix: String = "slime",
	private val priority: Int = 0,
) {
	init {
		require(sourcePrefix.isNotBlank()) { "sourcePrefix must not be blank" }
	}

	fun adapt(
		tracker: Tracker,
		observedAtNanos: Long,
		targetOverride: dev.slimevr.tracking.trackers.TrackerPosition? = null,
	): PoseObservation? {
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }

		val target = targetOverride ?: tracker.trackerPosition ?: return null
		val modality = tracker.sampleModality ?: when {
			tracker.hasPosition && tracker.hasRotation -> TrackingModality.FULL
			tracker.hasRotation -> TrackingModality.ROTATION_ONLY
			else -> TrackingModality.NONE
		}
		val statusQuality = if (tracker.status == TrackerStatus.OCCLUDED && modality == TrackingModality.ROTATION_ONLY)
			ObservationQuality.DEGRADED else tracker.status.toObservationQuality()
		val position = if (tracker.hasPosition && modality == TrackingModality.FULL) tracker.position else null
		val rotation = if (tracker.hasRotation && modality != TrackingModality.NONE) tracker.getRotation() else null

		return PoseObservation(
			sourceId = "$sourcePrefix:${tracker.name}",
			target = target,
			observedAtNanos = observedAtNanos,
			priority = priority,
			position = position,
			rotation = rotation,
			positionQuality = if (position != null) statusQuality else ObservationQuality.UNAVAILABLE,
			rotationQuality = if (rotation != null) statusQuality else ObservationQuality.UNAVAILABLE,
			modality = modality,
		)
	}

	private fun TrackerStatus.toObservationQuality(): ObservationQuality = when (this) {
		TrackerStatus.OK -> ObservationQuality.TRACKED
		TrackerStatus.BUSY -> ObservationQuality.DEGRADED
		TrackerStatus.TIMED_OUT -> ObservationQuality.STALE
		TrackerStatus.OCCLUDED,
		TrackerStatus.ERROR,
		TrackerStatus.DISCONNECTED,
		-> ObservationQuality.LOST
	}
}
