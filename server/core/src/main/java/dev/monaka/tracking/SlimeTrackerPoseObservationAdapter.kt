package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker

/**
 * Read-only adapter that mirrors an existing SlimeVR [Tracker] into Monaka's
 * backend-neutral [PoseObservation] model. It does not modify the tracker or the
 * existing HumanPoseManager/IK path.
 */
class SlimeTrackerPoseObservationAdapter(
	private val sourcePrefix: String = "slime",
	private val priority: Int = 0,
	private val receiptClock: () -> Long = System::nanoTime,
) {
	internal val independentImuCapture = SlimeIndependentImuOrientationCapture(receiptClock)
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
		val statusQuality = tracker.status.slimeObservationQuality(modality)
		val position = if (tracker.hasPosition && modality == TrackingModality.FULL) tracker.position else null
		val rotation = if (tracker.hasRotation && modality != TrackingModality.NONE) tracker.getRotation() else null
		// Both observation and raw predictor boundaries use the same physical capture contract.
		val sample = if (tracker.device != null && tracker.isImu() && rotation != null)
			(independentImuCapture.capture(tracker, observedAtNanos) as? SlimeImuCaptureResult.Available)?.sample else null
		val provenance = sample?.provenance

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
			provenance = provenance,
			correctionRotation = sample?.orientation,
		)
	}

}
