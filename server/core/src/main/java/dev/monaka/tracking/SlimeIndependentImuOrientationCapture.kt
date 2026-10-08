package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerStatus
import io.github.axisangles.ktmath.Quaternion

internal data class SlimeIndependentImuOrientationSample(
	val orientation: Quaternion,
	val provenance: ObservationSampleProvenance,
)

internal enum class SlimeImuCaptureRejection {
	SAMPLE_UNAVAILABLE, SAMPLE_UNSTABLE, SOURCE_EPOCH_CHANGED, CALIBRATION_EPOCH_CHANGED,
	SAMPLE_TIME_UNAVAILABLE, SAMPLE_AFTER_TICK_CUTOFF,
}

internal sealed interface SlimeImuCaptureResult {
	data class Available(val sample: SlimeIndependentImuOrientationSample) : SlimeImuCaptureResult
	data class Unavailable(val reason: SlimeImuCaptureRejection) : SlimeImuCaptureResult
}

/** Shared read-only capture. One instance maps each accepted sample into the runtime clock once. */
internal class SlimeIndependentImuOrientationCapture(
	private val receiptClock: () -> Long,
) {
	private val sampleTimes = java.util.WeakHashMap<Tracker, Pair<Long, Long>>()

	@Synchronized
	fun capture(tracker: Tracker, observedAtNanos: Long): SlimeImuCaptureResult = synchronized(tracker.resetsHandler) {
		synchronized(tracker) { captureLocked(tracker, observedAtNanos, null) }
	}

	/** Receipt cutoff is opaque System.nanoTime-domain data; this path reads no clock. */
	@Synchronized
	fun captureAtTick(tracker: Tracker, observedAtNanos: Long, receiptCutoffSystemNanos: Long): SlimeImuCaptureResult =
		synchronized(tracker.resetsHandler) {
			synchronized(tracker) { captureLocked(tracker, observedAtNanos, receiptCutoffSystemNanos) }
		}

	private fun captureLocked(tracker: Tracker, observedAtNanos: Long, cutoff: Long?): SlimeImuCaptureResult {
		require(observedAtNanos >= 0L)
		fun reject(reason: SlimeImuCaptureRejection) = SlimeImuCaptureResult.Unavailable(reason)
		val before = tracker.correctionOrientationSample()
			?: return reject(SlimeImuCaptureRejection.SAMPLE_UNAVAILABLE)
		if (cutoff != null && before.receivedAtSystemNanos > cutoff)
			return reject(SlimeImuCaptureRejection.SAMPLE_AFTER_TICK_CUTOFF)
		val sourceEpoch = tracker.correctionSourceEpoch
		val calibrationEpoch = tracker.resetsHandler.correctionCalibrationEpoch()
		// Tracker.setRotation holds the same monitor: raw value and receipt publication cannot split.
		val independent = tracker.resetsHandler.getCorrectionReferenceRotationFrom(tracker.getRawRotation())
		val receiptAnchor = cutoff ?: receiptClock()
		if (before != tracker.correctionOrientationSample())
			return reject(SlimeImuCaptureRejection.SAMPLE_UNSTABLE)
		if (sourceEpoch != tracker.correctionSourceEpoch)
			return reject(SlimeImuCaptureRejection.SOURCE_EPOCH_CHANGED)
		if (calibrationEpoch != tracker.resetsHandler.correctionCalibrationEpoch())
			return reject(SlimeImuCaptureRejection.CALIBRATION_EPOCH_CHANGED)
		val sampleAt = sampleTimes[tracker]?.takeIf { it.first == before.sequence }?.second ?: run {
			val age = try { Math.subtractExact(receiptAnchor, before.receivedAtSystemNanos) }
				catch (_: ArithmeticException) { return reject(SlimeImuCaptureRejection.SAMPLE_TIME_UNAVAILABLE) }
			if (age < 0 || age > observedAtNanos) return reject(SlimeImuCaptureRejection.SAMPLE_TIME_UNAVAILABLE)
			(observedAtNanos - age).also { sampleTimes[tracker] = before.sequence to it }
		}
		return SlimeImuCaptureResult.Available(SlimeIndependentImuOrientationSample(independent,
			ObservationSampleProvenance(before.sequence, sampleAt, sourceEpoch, calibrationEpoch)))
	}
}

internal fun TrackerStatus.slimeObservationQuality(modality: TrackingModality): ObservationQuality = when (this) {
	TrackerStatus.OK -> ObservationQuality.TRACKED
	TrackerStatus.BUSY -> ObservationQuality.DEGRADED
	TrackerStatus.TIMED_OUT -> ObservationQuality.STALE
	TrackerStatus.OCCLUDED -> if (modality == TrackingModality.ROTATION_ONLY)
		ObservationQuality.DEGRADED else ObservationQuality.LOST
	TrackerStatus.ERROR, TrackerStatus.DISCONNECTED -> ObservationQuality.LOST
}
