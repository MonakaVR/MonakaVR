package dev.monaka.tracking

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/** Dormant explicit rigid relation from raw HMD frame H to model HEAD root anchor A.
 * A uses the reference axes supplied to Bone.setRotation, before its geometry rotationOffset.
 * t_HA is metres from H origin to A origin in H-local axes; q_HA = q(H <- A).
 * Contract: p_WA = p_WH + q_WH.sandwich(t_HA); q_WA = q_WH * q_HA.
 * This value establishes neither world-space proof nor physical acquisition authority.
 * No body chain, Main teacher, IMU mount/reset, provider calibration or runtime is accessed.
 */
class PredictorFixedCalibrationSnapshot private constructor(
	val identity: FixedCalibrationIdentity,
	val hmdSourceId: String,
	val bodyModelId: String,
	val sessionEpoch: String,
	val hmdToHeadAnchorLocalOffset: Vector3,
	val hmdToHeadAnchorOrientation: Quaternion,
) {
	companion object {
		const val EPOCH_SCHEMA = "predictor-fixed-v1"

		/** All values are mandatory. Identity rotation/zero offset require explicit caller selection.
		 * sessionEpoch identifies physical fit/recalibration or loaded calibration session, not samples.
		 * Effective epoch is factory-owned; body content and world/provider epochs are independent.
		 */
		fun create(calibrationId: String, sessionEpoch: String, hmdSourceId: String, bodyModelId: String,
			hmdToHeadAnchorLocalOffset: Vector3,
			hmdToHeadAnchorOrientation: Quaternion): PredictorFixedCalibrationSnapshotResult {
			if (calibrationId.isBlank()) return reject(PredictorFixedCalibrationRejectionReason.CALIBRATION_ID_BLANK)
			if (sessionEpoch.isBlank()) return reject(PredictorFixedCalibrationRejectionReason.SESSION_EPOCH_BLANK)
			if (hmdSourceId.isBlank()) return reject(PredictorFixedCalibrationRejectionReason.HMD_SOURCE_ID_BLANK)
			if (bodyModelId.isBlank()) return reject(PredictorFixedCalibrationRejectionReason.BODY_MODEL_ID_BLANK)
			val offset = hmdToHeadAnchorLocalOffset
			if (!listOf(offset.x, offset.y, offset.z).all(Float::isFinite))
				return reject(PredictorFixedCalibrationRejectionReason.OFFSET_NONFINITE)
			val q = canonicalOrientation(hmdToHeadAnchorOrientation)
				?: return reject(PredictorFixedCalibrationRejectionReason.ORIENTATION_INVALID)
			// Length-prefixed opaque strings; fixed order and exact 8-digit Float raw bits.
			// Translation preserves signed zero (body-model/Main-mount precedent); rotation zeros do not.
			val epoch = "$EPOCH_SCHEMA;source=${hmdSourceId.length}:$hmdSourceId;" +
				"model=${bodyModelId.length}:$bodyModelId;session=${sessionEpoch.length}:$sessionEpoch;" +
				listOf(offset.x, offset.y, offset.z, q.w, q.x, q.y, q.z)
					.joinToString(";") { it.toRawBits().toUInt().toString(16).padStart(8, '0') }
			return PredictorFixedCalibrationSnapshotResult.Available(PredictorFixedCalibrationSnapshot(
				FixedCalibrationIdentity(calibrationId, epoch), hmdSourceId, bodyModelId, sessionEpoch, offset, q))
		}

		private fun reject(reason: PredictorFixedCalibrationRejectionReason) =
			PredictorFixedCalibrationSnapshotResult.Unavailable(reason)

		private fun canonicalOrientation(q: Quaternion): Quaternion? {
			val components = listOf(q.w, q.x, q.y, q.z)
			if (!components.all(Float::isFinite)) return null
			val scale = components.maxOf { abs(it) }
			if (scale == 0f) return null
			// Same safe scaling as 5V: direct component division avoids reciprocal overflow
			// for subnormal finite nonzero values; scaled lenSq is bounded to [1,4].
			val unit = Quaternion(q.w / scale, q.x / scale, q.y / scale, q.z / scale).unit()
			val normalized = listOf(unit.w, unit.x, unit.y, unit.z)
			if (!normalized.all(Float::isFinite) || !unit.lenSq().isFinite() || unit.lenSq() == 0f) return null
			val sign = if (normalized.first { it != 0f } < 0f) -1f else 1f
			fun canonical(value: Float) = if (value == 0f) 0f else value * sign
			return Quaternion(canonical(unit.w), canonical(unit.x), canonical(unit.y), canonical(unit.z))
		}
	}
}

enum class PredictorFixedCalibrationRejectionReason {
	CALIBRATION_ID_BLANK, SESSION_EPOCH_BLANK, HMD_SOURCE_ID_BLANK, BODY_MODEL_ID_BLANK,
	OFFSET_NONFINITE, ORIENTATION_INVALID,
}

sealed interface PredictorFixedCalibrationSnapshotResult {
	data class Available(val snapshot: PredictorFixedCalibrationSnapshot) : PredictorFixedCalibrationSnapshotResult
	data class Unavailable(val reason: PredictorFixedCalibrationRejectionReason) : PredictorFixedCalibrationSnapshotResult
}
