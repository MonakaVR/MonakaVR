package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition

/** Caller-established exact interpretation of the fixed-reference orientation, not provider proof. */
internal data class SlimeRawImuCoordinateSpaceBinding(
	val sourceId: String,
	val space: CoordinateSpace,
	val operatorConfirmed: Boolean,
)

internal enum class SlimeRawImuInputRejectionReason {
	TRACKER_NOT_PHYSICAL, TRACKER_NOT_IMU, TRACKER_INTERNAL, TRACKER_COMPUTED, TRACKER_IS_HMD,
	TARGET_NOT_HIP, ROTATION_UNAVAILABLE, STATUS_UNUSABLE, FEEDBACK_SOURCE_NOT_ALLOWED,
	SAMPLE_UNAVAILABLE, SAMPLE_UNSTABLE, SOURCE_EPOCH_CHANGED, CALIBRATION_EPOCH_CHANGED,
	SAMPLE_TIME_UNAVAILABLE, ORIENTATION_INVALID,
	SPACE_BINDING_UNCONFIRMED, SPACE_BINDING_SOURCE_MISMATCH, SPACE_INVALID,
}

internal sealed interface SlimeRawImuInputResult {
	data class Available(val input: RawImuOrientationInput) : SlimeRawImuInputResult
	data class Unavailable(val reason: SlimeRawImuInputRejectionReason) : SlimeRawImuInputResult
}

/** Dormant production value boundary. No resolver, Phase 1 learner, predictor or IK consumer. */
internal class SlimeRawImuProductionBoundary(
	private val capture: SlimeIndependentImuOrientationCapture = SlimeIndependentImuOrientationCapture(System::nanoTime),
	private val sourcePrefix: String = "slime",
) {
	init { require(sourcePrefix.isNotBlank()) }

	fun adapt(tracker: Tracker, observedAtNanos: Long,
		binding: SlimeRawImuCoordinateSpaceBinding): SlimeRawImuInputResult {
		require(observedAtNanos >= 0L)
		fun reject(reason: SlimeRawImuInputRejectionReason) = SlimeRawImuInputResult.Unavailable(reason)
		val sourceId = "$sourcePrefix:${tracker.name}"
		if (FeedbackExclusion.isOutput(tracker.name) || FeedbackExclusion.isOutput(sourceId))
			return reject(SlimeRawImuInputRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED)
		if (tracker.device == null) return reject(SlimeRawImuInputRejectionReason.TRACKER_NOT_PHYSICAL)
		if (tracker.isInternal) return reject(SlimeRawImuInputRejectionReason.TRACKER_INTERNAL)
		if (tracker.isComputed) return reject(SlimeRawImuInputRejectionReason.TRACKER_COMPUTED)
		if (tracker.isHmd) return reject(SlimeRawImuInputRejectionReason.TRACKER_IS_HMD)
		if (!tracker.isImu()) return reject(SlimeRawImuInputRejectionReason.TRACKER_NOT_IMU)
		if (tracker.trackerPosition != TrackerPosition.HIP) return reject(SlimeRawImuInputRejectionReason.TARGET_NOT_HIP)
		val modality = tracker.sampleModality ?: if (tracker.hasPosition && tracker.hasRotation)
			TrackingModality.FULL else TrackingModality.ROTATION_ONLY
		if (!tracker.hasRotation || modality == TrackingModality.NONE)
			return reject(SlimeRawImuInputRejectionReason.ROTATION_UNAVAILABLE)
		if (!tracker.status.slimeObservationQuality(modality).usable)
			return reject(SlimeRawImuInputRejectionReason.STATUS_UNUSABLE)
		if (!binding.operatorConfirmed) return reject(SlimeRawImuInputRejectionReason.SPACE_BINDING_UNCONFIRMED)
		if (binding.sourceId.isBlank() || binding.sourceId != sourceId)
			return reject(SlimeRawImuInputRejectionReason.SPACE_BINDING_SOURCE_MISMATCH)
		val space = binding.space
		if (space.id.isBlank() || space.revision < 0 || space.convention != "rh_y_up_neg_z_forward")
			return reject(SlimeRawImuInputRejectionReason.SPACE_INVALID)
		val sample = when (val result = capture.capture(tracker, observedAtNanos)) {
			is SlimeImuCaptureResult.Available -> result.sample
			is SlimeImuCaptureResult.Unavailable -> return reject(when (result.reason) {
				SlimeImuCaptureRejection.SAMPLE_UNAVAILABLE -> SlimeRawImuInputRejectionReason.SAMPLE_UNAVAILABLE
				SlimeImuCaptureRejection.SAMPLE_UNSTABLE -> SlimeRawImuInputRejectionReason.SAMPLE_UNSTABLE
				SlimeImuCaptureRejection.SOURCE_EPOCH_CHANGED -> SlimeRawImuInputRejectionReason.SOURCE_EPOCH_CHANGED
				SlimeImuCaptureRejection.CALIBRATION_EPOCH_CHANGED -> SlimeRawImuInputRejectionReason.CALIBRATION_EPOCH_CHANGED
				SlimeImuCaptureRejection.SAMPLE_TIME_UNAVAILABLE -> SlimeRawImuInputRejectionReason.SAMPLE_TIME_UNAVAILABLE
			})
		}
		val q = sample.orientation
		if (!listOf(q.w, q.x, q.y, q.z).all(Float::isFinite) || !q.lenSq().isFinite() || q.lenSq() <= 1e-10f)
			return reject(SlimeRawImuInputRejectionReason.ORIENTATION_INVALID)
		// Fixed transforms preserve norm for unit inputs. Normalize valid non-unit inputs at this boundary only.
		return SlimeRawImuInputResult.Available(RawImuOrientationInput(
			RawSourceIdentity(sourceId, RawSourceKind.RAW_IMU), q.unit(), space,
			sample.provenance.copy(space = space)))
	}
}
