package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/** Teacher-side identity only; never a predictor FixedCalibrationIdentity. */
internal data class MainTrackerMountCalibrationIdentity(val calibrationId: String, val epoch: String) {
	init { require(calibrationId.isNotBlank() && epoch.isNotBlank()) }
}

/** Immutable metres from tracker origin to HIP_CENTER, expressed in the raw tracker's local axes.
 * Only create() can bind content to identity; no copy() can retain an epoch with changed values.
 */
internal class MainTrackerMountCalibrationSnapshot private constructor(
	val identity: MainTrackerMountCalibrationIdentity,
	val sourceId: String,
	val sessionEpoch: String,
	val trackerToHipCenterLocalOffset: Vector3,
) {
	companion object {
		const val EPOCH_SCHEMA = "main-mount-v1"

		fun create(calibrationId: String, sessionEpoch: String, sourceId: String,
			trackerToHipCenterLocalOffset: Vector3): MainTrackerMountCalibrationSnapshotResult {
			if (calibrationId.isBlank() || sessionEpoch.isBlank() || sourceId.isBlank() ||
				!finiteMountVector(trackerToHipCenterLocalOffset))
				return MainTrackerMountCalibrationSnapshotResult.Unavailable(MainHipCenterTeacherRejectionReason.CALIBRATION_INVALID)
			// Length-prefixed opaque strings and fixed-width raw bits: deterministic and unambiguous.
			// Physical remount/recalibration changes sessionEpoch even if the numeric offset is identical.
			val epoch = "$EPOCH_SCHEMA;source=${sourceId.length}:$sourceId;session=${sessionEpoch.length}:$sessionEpoch;" +
				listOf(trackerToHipCenterLocalOffset.x, trackerToHipCenterLocalOffset.y,
					trackerToHipCenterLocalOffset.z).joinToString(";") { it.toRawBits().toUInt().toString(16).padStart(8, '0') }
			return MainTrackerMountCalibrationSnapshotResult.Available(MainTrackerMountCalibrationSnapshot(
				MainTrackerMountCalibrationIdentity(calibrationId, epoch), sourceId, sessionEpoch,
				trackerToHipCenterLocalOffset))
		}
	}
}

internal sealed interface MainTrackerMountCalibrationSnapshotResult {
	data class Available(val snapshot: MainTrackerMountCalibrationSnapshot) : MainTrackerMountCalibrationSnapshotResult
	data class Unavailable(val reason: MainHipCenterTeacherRejectionReason) : MainTrackerMountCalibrationSnapshotResult
}

/** Position-only derived teacher; not a PoseObservation or HIP body orientation.
 * Raw physical provenance remains intact; the additional mount identity is a separate lineage.
 */
internal data class MainHipCenterPositionTeacher(
	val sourceId: String,
	val position: Vector3,
	val positionQuality: ObservationQuality,
	val provenance: ObservationSampleProvenance,
	val rawOrigin: RawSourceIdentity,
	val mountCalibration: MainTrackerMountCalibrationIdentity,
) {
	val target: TrackerPosition get() = TrackerPosition.HIP
	val bodyReference: PositionBodyReference get() = PositionBodyReference.HIP_CENTER
	val space: CoordinateSpace get() = requireNotNull(provenance.space)
}

internal enum class MainHipCenterTeacherRejectionReason {
	FEEDBACK_SOURCE_NOT_ALLOWED, SOURCE_NOT_RAW_BACKEND, SOURCE_IDENTITY_MISMATCH,
	CALIBRATION_SOURCE_MISMATCH, CALIBRATION_INVALID, TARGET_NOT_HIP, MODALITY_NOT_FULL,
	POSITION_UNAVAILABLE, ROTATION_UNAVAILABLE, POSITION_QUALITY_UNUSABLE, ROTATION_QUALITY_UNUSABLE,
	POSITION_NONFINITE, ORIENTATION_INVALID, PROVENANCE_UNAVAILABLE, SPACE_UNAVAILABLE,
	SPACE_UNSUPPORTED, TRANSFORM_NONFINITE,
}

internal sealed interface MainHipCenterTeacherResult {
	data class Available(val teacher: MainHipCenterPositionTeacher) : MainHipCenterTeacherResult
	data class Unavailable(val reason: MainHipCenterTeacherRejectionReason) : MainHipCenterTeacherResult
}

/** Dormant pure geometry boundary. Caller supplies trusted raw TRACKER_MOUNT ingress and explicit
 * current calibration. No clock, reset transform, correctionRotation, runtime wiring or pairing.
 */
internal object MainTrackerMountToHipCenter {
	private fun reject(reason: MainHipCenterTeacherRejectionReason) = MainHipCenterTeacherResult.Unavailable(reason)

	fun normalize(observation: PoseObservation, rawOrigin: RawSourceIdentity,
		calibration: MainTrackerMountCalibrationSnapshot): MainHipCenterTeacherResult {
		if (FeedbackExclusion.isOutput(observation.sourceId) || FeedbackExclusion.isOutput(rawOrigin.sourceId) ||
			rawOrigin.isInternal || rawOrigin.isComputed || rawOrigin.kind in
			setOf(RawSourceKind.COMPUTED_TRACKER, RawSourceKind.DERIVED_OUTPUT))
			return reject(MainHipCenterTeacherRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED)
		if (!rawOrigin.isRawBackend()) return reject(MainHipCenterTeacherRejectionReason.SOURCE_NOT_RAW_BACKEND)
		if (rawOrigin.sourceId != observation.sourceId) return reject(MainHipCenterTeacherRejectionReason.SOURCE_IDENTITY_MISMATCH)
		if (calibration.sourceId != observation.sourceId) return reject(MainHipCenterTeacherRejectionReason.CALIBRATION_SOURCE_MISMATCH)
		if (observation.target != TrackerPosition.HIP) return reject(MainHipCenterTeacherRejectionReason.TARGET_NOT_HIP)
		if (observation.modality != TrackingModality.FULL) return reject(MainHipCenterTeacherRejectionReason.MODALITY_NOT_FULL)
		val position = observation.position ?: return reject(MainHipCenterTeacherRejectionReason.POSITION_UNAVAILABLE)
		val rotation = observation.rotation ?: return reject(MainHipCenterTeacherRejectionReason.ROTATION_UNAVAILABLE)
		if (!observation.positionQuality.usable) return reject(MainHipCenterTeacherRejectionReason.POSITION_QUALITY_UNUSABLE)
		if (!observation.rotationQuality.usable) return reject(MainHipCenterTeacherRejectionReason.ROTATION_QUALITY_UNUSABLE)
		if (!finiteMountVector(position)) return reject(MainHipCenterTeacherRejectionReason.POSITION_NONFINITE)
		val orientation = normalizedOrientation(rotation) ?: return reject(MainHipCenterTeacherRejectionReason.ORIENTATION_INVALID)
		val provenance = observation.provenance ?: return reject(MainHipCenterTeacherRejectionReason.PROVENANCE_UNAVAILABLE)
		val space = provenance.space ?: return reject(MainHipCenterTeacherRejectionReason.SPACE_UNAVAILABLE)
		if (space.id.isBlank() || space.revision < 0 || space.convention != "rh_y_up_neg_z_forward")
			return reject(MainHipCenterTeacherRejectionReason.SPACE_UNSUPPORTED)
		// Hamilton q * (0, offset) * q^-1, using this exact sample's tracker-to-space rotation.
		val hipCenter = position + orientation.sandwich(calibration.trackerToHipCenterLocalOffset)
		if (!finiteMountVector(hipCenter)) return reject(MainHipCenterTeacherRejectionReason.TRANSFORM_NONFINITE)
		return MainHipCenterTeacherResult.Available(MainHipCenterPositionTeacher(
			observation.sourceId, hipCenter, observation.positionQuality, provenance, rawOrigin, calibration.identity))
	}

	private fun normalizedOrientation(q: Quaternion): Quaternion? {
		val components = listOf(q.w, q.x, q.y, q.z)
		if (!components.all(Float::isFinite)) return null
		val scale = components.maxOf { abs(it) }
		if (scale == 0f) return null
		// Direct component division avoids ktmath Float division's reciprocal overflow for tiny q.
		// Scale bounds lenSq to [1,4] before using the existing nonmutating unit() helper.
		return Quaternion(q.w / scale, q.x / scale, q.y / scale, q.z / scale).unit()
	}
}

private fun finiteMountVector(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
