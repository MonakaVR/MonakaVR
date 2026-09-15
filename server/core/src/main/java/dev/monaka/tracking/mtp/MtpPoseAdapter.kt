package dev.monaka.tracking.mtp

import dev.monaka.protocol.v1.MtpPose
import dev.monaka.tracking.*
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** C1 is already calibrated. No vendor mapper, mounting transform or confidence fusion. */
class MtpPoseAdapter {
	fun adapt(pose: MtpPose, target: TrackerPosition, sampleTime: Long): PoseObservation {
		fun floats(values: List<Double>): List<Float> = values.map {
			require(it.isFinite() && it.toFloat().isFinite()) { "C1 value exceeds runtime Float range" }
			it.toFloat()
		}
		val available = pose.tracking_state == "tracked" || pose.tracking_state == "degraded"
		val p = if (available && pose.validity.position && pose.confidence.position > 0) {
			floats(requireNotNull(pose.position)).let { Vector3(it[0], it[1], it[2]) }
		} else null
		val q = if (available && pose.validity.orientation && pose.confidence.orientation > 0) {
			floats(requireNotNull(pose.orientation)).let { Quaternion(it[3], it[0], it[1], it[2]) }
		} else null
		val quality = if (pose.tracking_state == "tracked") ObservationQuality.TRACKED else ObservationQuality.DEGRADED
		return PoseObservation(
			LogicalTracker(pose.source_id, pose.tracker_id).observationId, target, sampleTime,
			position = p, rotation = q,
			positionQuality = if (p == null) ObservationQuality.LOST else quality,
			rotationQuality = if (q == null) ObservationQuality.LOST else quality,
		)
	}
}
