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
	): PoseObservation? {
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }

		val target = tracker.trackerPosition ?: return null
		val statusQuality = tracker.status.toObservationQuality()
		val position = if (tracker.hasPosition) tracker.position else null
		val rotation = if (tracker.hasRotation) tracker.getRotation() else null

		return PoseObservation(
			sourceId = "$sourcePrefix:${tracker.id}",
			target = target,
			observedAtNanos = observedAtNanos,
			priority = priority,
			position = position,
			rotation = rotation,
			positionQuality = if (tracker.hasPosition) statusQuality else ObservationQuality.UNAVAILABLE,
			rotationQuality = if (tracker.hasRotation) statusQuality else ObservationQuality.UNAVAILABLE,
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
