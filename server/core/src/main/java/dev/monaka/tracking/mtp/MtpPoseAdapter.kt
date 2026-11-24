package dev.monaka.tracking.mtp

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.ObservationQuality
import dev.monaka.tracking.ObservationSampleProvenance
import dev.monaka.tracking.PoseObservation
import dev.monaka.tracking.TrackingModality
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** MTP positions and orientations arrive in the publisher's normalized output basis. */
class MtpPoseAdapter {
	fun adapt(pose: MtpPose, target: TrackerPosition, sampleTime: Long): PoseObservation =
		adapt(pose.toNormalMtpPose(), target, sampleTime, pose.sequence, pose.coordinate_space, pose.mapping_revision)

	internal fun adapt(sample: MtpObservationBackend.Sample, target: TrackerPosition): PoseObservation =
		adapt(sample.pose, target, sample.sampleTime, sample.acquisitionSequence, sample.legacySpace, sample.legacyMappingRevision)

	private fun adapt(
		pose: NormalMtpPose,
		target: TrackerPosition,
		sampleTime: Long,
		acquisitionSequence: Long,
		legacySpace: CoordinateSpace?,
		legacyMappingRevision: Long?,
	): PoseObservation {
		fun floats(values: List<Double>): List<Float> = values.map {
			require(it.isFinite() && it.toFloat().isFinite()) { "MTP value exceeds runtime Float range" }
			it.toFloat()
		}
		val available = pose.tracking_state == "tracked" || pose.tracking_state == "degraded"
		val p = if (available && pose.modality == "full" && pose.positionValid && pose.positionConfidence > 0) {
			floats(requireNotNull(pose.position)).let { Vector3(it[0], it[1], it[2]) }
		} else {
			null
		}
		val q = if (available && pose.modality != "none" && pose.orientationValid && pose.orientationConfidence > 0) {
			floats(requireNotNull(pose.orientation)).let { Quaternion(it[3], it[0], it[1], it[2]) }
		} else {
			null
		}
		val quality = if (pose.tracking_state == "tracked") ObservationQuality.TRACKED else ObservationQuality.DEGRADED
		return PoseObservation(
			LogicalTracker(pose.source_id, pose.tracker_id, pose.publisher_id).observationId, target, sampleTime,
			position = p, rotation = q, modality = TrackingModality.valueOf(pose.modality.uppercase()),
			positionQuality = if (p == null) ObservationQuality.LOST else quality,
			rotationQuality = if (q == null) ObservationQuality.LOST else quality,
			provenance = ObservationSampleProvenance(
				acquisitionSequence,
				sampleTime,
				"${pose.session_id}:${pose.clock_id}",
				pose.input.session_id,
				legacyMappingRevision,
				legacySpace,
				convention = pose.convention.takeIf { legacySpace == null },
			),
			correctionRotation = q,
		)
	}
}
