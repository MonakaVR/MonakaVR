package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Derived pre-IK value, deliberately neither a raw observation nor a pure prediction. */
internal data class PositionCorrectionApplicationCandidate(
	val target: TrackerPosition,
	val bodyReference: PositionBodyReference,
	val coordinateSpace: CoordinateSpace,
	val basePredictionPosition: Vector3,
	val correctionWorld: Vector3,
	val correctedPosition: Vector3,
	val predictionSequence: Long,
	val predictionEpoch: PositionPredictionEpoch,
	val predictionProvenance: PositionPredictionProvenance,
	val correctionPhase: PositionCorrectionPhase,
	val correctionLineage: PositionCorrectionLearningLineage,
	val applicationAtNanos: Long,
	val positionObservedAtNanos: Long,
	val positionSourceId: String,
	val baseRotation: ResolvedComponent<Quaternion>,
) {
	fun ikConstraint() = EffectiveConstraint(target,
		ResolvedComponent(correctedPosition, positionSourceId, ObservationQuality.DEGRADED, positionObservedAtNanos),
		baseRotation)
}

internal enum class PositionCorrectionApplicationRejectionReason {
	APPLICATION_TIME_INVALID,
	PREDICTION_UNAVAILABLE, PREDICTION_TARGET_MISMATCH, PREDICTION_BODY_REFERENCE_MISMATCH,
	PREDICTION_STRUCTURALLY_INELIGIBLE, PREDICTION_SPACE_MISMATCH, PREDICTION_FEEDBACK_SOURCE,
	PREDICTION_NOT_CURRENT_FRAME,
	STATE_UNINITIALIZED, STATE_LINEAGE_UNAVAILABLE, STATE_NOT_ADVANCED_TO_APPLICATION_TIME,
	STATE_SPACE_MISMATCH, STATE_PREDICTION_EPOCH_MISMATCH, STATE_CORRECTION_NONFINITE,
	STATE_EXPIRED_CORRECTION_NONZERO,
	ASSIGNMENT_GENERATION_MISMATCH, ASSIGNMENT_MISSING, IK_CONSTRAINT_DISABLED, ROTATION_FALLBACK_UNASSIGNED,
	BASE_TARGET_MISMATCH, BASE_POSITION_ALREADY_PRESENT, BASE_ROTATION_UNAVAILABLE,
	BASE_ROTATION_FEEDBACK_SOURCE, BASE_ROTATION_SOURCE_MISMATCH, BASE_ROTATION_QUALITY_UNUSABLE,
	BASE_ROTATION_INVALID, BASE_ROTATION_TIME_INVALID,
	CORRECTED_POSITION_NONFINITE,
}

internal sealed interface PositionCorrectionApplicationResult {
	data class Ready(val candidate: PositionCorrectionApplicationCandidate) : PositionCorrectionApplicationResult
	data class Rejected(
		val reason: PositionCorrectionApplicationRejectionReason,
		val structuralReason: String? = null,
	) : PositionCorrectionApplicationResult
}

/** Pure/dormant fallback-position overlay. Caller owns selection, clocks and state advancement.
 * Absolute construction and position-present rejection guard double application without counters.
 * No teacher reread, learning, store ingress, output conversion or production writeback.
 */
internal object PositionCorrectionApplication {
	fun prepare(
		prediction: PositionPrediction,
		state: PositionCorrectionStateSnapshot,
		baseConstraint: EffectiveConstraint,
		assignment: TrackerBodyAssignments.Snapshot,
		expectedSpace: CoordinateSpace,
		applicationAtNanos: Long,
	): PositionCorrectionApplicationResult {
		fun reject(reason: PositionCorrectionApplicationRejectionReason) = PositionCorrectionApplicationResult.Rejected(reason)
		if (applicationAtNanos < 0) return reject(PositionCorrectionApplicationRejectionReason.APPLICATION_TIME_INVALID)
		if (prediction.validity != PredictionValidity.AVAILABLE || prediction.position == null)
			return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_UNAVAILABLE)
		if (prediction.target != TrackerPosition.HIP) return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_TARGET_MISMATCH)
		if (prediction.bodyReference != PositionBodyReference.HIP_CENTER)
			return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_BODY_REFERENCE_MISMATCH)
		val structural = PositionCorrectionTeacherEligibility.check(prediction)
		if (!structural.eligibleForPairing) return PositionCorrectionApplicationResult.Rejected(
			PositionCorrectionApplicationRejectionReason.PREDICTION_STRUCTURALLY_INELIGIBLE, structural.reason)
		val provenance = prediction.provenance!!
		val epoch = provenance.epoch
		if (FeedbackExclusion.isOutput(epoch.hmdSourceId) || FeedbackExclusion.isOutput(epoch.imuSourceId))
			return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_FEEDBACK_SOURCE)
		if (provenance.generatedAtNanos != applicationAtNanos)
			return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_NOT_CURRENT_FRAME)
		if (state.phase == PositionCorrectionPhase.UNINITIALIZED)
			return reject(PositionCorrectionApplicationRejectionReason.STATE_UNINITIALIZED)
		val lineage = state.lineage ?: return reject(PositionCorrectionApplicationRejectionReason.STATE_LINEAGE_UNAVAILABLE)
		if (state.lastStateAdvanceAtNanos != applicationAtNanos)
			return reject(PositionCorrectionApplicationRejectionReason.STATE_NOT_ADVANCED_TO_APPLICATION_TIME)
		if (prediction.space != expectedSpace || epoch.coordinateSpace != expectedSpace)
			return reject(PositionCorrectionApplicationRejectionReason.PREDICTION_SPACE_MISMATCH)
		if (state.coordinateSpace != expectedSpace || lineage.predictionEpoch.coordinateSpace != expectedSpace ||
			lineage.teacherEpoch.coordinateSpace != expectedSpace)
			return reject(PositionCorrectionApplicationRejectionReason.STATE_SPACE_MISMATCH)
		if (lineage.predictionEpoch != epoch)
			return reject(PositionCorrectionApplicationRejectionReason.STATE_PREDICTION_EPOCH_MISMATCH)
		if (assignment.generation != epoch.assignmentGeneration || assignment.generation != lineage.predictionEpoch.assignmentGeneration ||
			assignment.generation != lineage.teacherEpoch.assignmentGeneration)
			return reject(PositionCorrectionApplicationRejectionReason.ASSIGNMENT_GENERATION_MISMATCH)
		val relation = assignment.targets[TrackerPosition.HIP]
			?: return reject(PositionCorrectionApplicationRejectionReason.ASSIGNMENT_MISSING)
		if (!relation.useAsIkConstraint) return reject(PositionCorrectionApplicationRejectionReason.IK_CONSTRAINT_DISABLED)
		val fallback = relation.rotationFallbackTracker
			?: return reject(PositionCorrectionApplicationRejectionReason.ROTATION_FALLBACK_UNASSIGNED)
		if (baseConstraint.target != TrackerPosition.HIP) return reject(PositionCorrectionApplicationRejectionReason.BASE_TARGET_MISMATCH)
		if (baseConstraint.position != null) return reject(PositionCorrectionApplicationRejectionReason.BASE_POSITION_ALREADY_PRESENT)
		val rotation = baseConstraint.rotation ?: return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_UNAVAILABLE)
		if (FeedbackExclusion.isOutput(rotation.sourceId))
			return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_FEEDBACK_SOURCE)
		if (fallback.observationId != rotation.sourceId || rotation.sourceId != epoch.imuSourceId)
			return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_SOURCE_MISMATCH)
		if (!rotation.quality.usable) return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_QUALITY_UNUSABLE)
		val q = rotation.value
		val lenSq = q.lenSq()
		if (!listOf(q.w, q.x, q.y, q.z).all(Float::isFinite) || !lenSq.isFinite() || lenSq <= 1e-10f)
			return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_INVALID)
		if (rotation.observedAtNanos < 0 || rotation.observedAtNanos > applicationAtNanos)
			return reject(PositionCorrectionApplicationRejectionReason.BASE_ROTATION_TIME_INVALID)
		val base = prediction.position.let { Vector3(it.x, it.y, it.z) }
		val correction = state.correctionWorld.let { Vector3(it.x, it.y, it.z) }
		if (!finite(correction)) return reject(PositionCorrectionApplicationRejectionReason.STATE_CORRECTION_NONFINITE)
		if (state.phase == PositionCorrectionPhase.EXPIRED && (correction.x != 0f || correction.y != 0f || correction.z != 0f))
			return reject(PositionCorrectionApplicationRejectionReason.STATE_EXPIRED_CORRECTION_NONZERO)
		val corrected = Vector3(base.x + correction.x, base.y + correction.y, base.z + correction.z)
		if (!finite(corrected)) return reject(PositionCorrectionApplicationRejectionReason.CORRECTED_POSITION_NONFINITE)
		return PositionCorrectionApplicationResult.Ready(PositionCorrectionApplicationCandidate(
			TrackerPosition.HIP, PositionBodyReference.HIP_CENTER, expectedSpace, base, correction, corrected,
			provenance.predictionSequence, epoch, provenance, state.phase, lineage, applicationAtNanos,
			provenance.inputEarliestAtNanos, "monaka-private:position-correction-v1:HIP", rotation))
	}

	private fun finite(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
}
