package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace

/** Explicit caller policy, in local monotonic nanoseconds. Zero means exact-only; no runtime default. */
internal data class PositionTemporalPairingPolicy(
	val maxTeacherToPredictionInputSkewNanos: Long,
	val maxTeacherAgeNanos: Long,
	val maxPredictionInputAgeNanos: Long,
	val maxPredictionGenerationAgeNanos: Long,
) {
	init {
		require(maxTeacherToPredictionInputSkewNanos >= 0 && maxTeacherAgeNanos >= 0 &&
			maxPredictionInputAgeNanos >= 0 && maxPredictionGenerationAgeNanos >= 0)
	}
}

/** Raw Main continuity, distinct from prediction input lineage. Sample progression is not identity. */
internal data class PositionTeacherEpoch(
	val sourceId: String,
	val sourceEpoch: String,
	val calibrationEpoch: String,
	val mappingRevision: Long?,
	val coordinateSpace: CoordinateSpace,
	val assignmentGeneration: Long,
	val bodyReference: PositionBodyReference,
	val mountCalibration: MainTrackerMountCalibrationIdentity,
	val commonWorldEpoch: String? = null,
) {
	init {
		require(sourceId.isNotBlank() && sourceEpoch.isNotBlank() && calibrationEpoch.isNotBlank())
		require(assignmentGeneration >= 0 && bodyReference != PositionBodyReference.UNKNOWN)
		require(commonWorldEpoch == null || commonWorldEpoch.isNotBlank())
	}

	companion object {
		/** Extraction only; does not confer structural eligibility, current-context proof or authority. */
		fun from(input: PositionCorrectionInput): PositionTeacherEpoch? {
			val main = input.mainTeacher
			val provenance = main.provenance
			val space = provenance.space ?: return null
			val bodyReference = main.bodyReference
			if (input.assignmentGeneration < 0 || main.sourceId.isBlank() ||
				main.rawOrigin.sourceId != main.sourceId) return null
			return PositionTeacherEpoch(
				sourceId = main.sourceId,
				sourceEpoch = provenance.sourceEpoch,
				calibrationEpoch = provenance.calibrationEpoch,
				mappingRevision = provenance.mappingRevision,
				coordinateSpace = space,
				assignmentGeneration = input.assignmentGeneration,
				bodyReference = bodyReference,
				mountCalibration = main.mountCalibration,
				commonWorldEpoch = provenance.commonWorldEpoch,
			)
		}
	}
}

internal enum class PositionTemporalPairingRejectionReason {
	STRUCTURAL_INELIGIBLE,
	TEACHER_EPOCH_UNAVAILABLE,
	TEACHER_EPOCH_MISMATCH,
	PREDICTION_EPOCH_MISMATCH,
	SPACE_MISMATCH,
	ASSIGNMENT_GENERATION_MISMATCH,
	FUTURE_TEACHER_SAMPLE,
	FUTURE_PREDICTION_INPUT,
	FUTURE_PREDICTION_GENERATION,
	TEACHER_STALE,
	PREDICTION_INPUT_STALE,
	PREDICTION_GENERATION_STALE,
	PAIRING_SKEW_EXCEEDED,
}

internal sealed interface PositionTemporalPairingResult {
	/** Immutable comparison facts only. Pairable does not mean learning is allowed. */
	data class Pairable(
		val teacherSampleAtNanos: Long,
		val predictionInputEarliestAtNanos: Long,
		val predictionInputLatestAtNanos: Long,
		val teacherToPredictionInputDistanceNanos: Long,
		val teacherEpoch: PositionTeacherEpoch,
		val predictionEpoch: PositionPredictionEpoch,
	) : PositionTemporalPairingResult

	data class Rejected(
		val reason: PositionTemporalPairingRejectionReason,
		val structuralReason: String? = null,
	) : PositionTemporalPairingResult
}

/** Stateless dormant boundary. No clock reads, learner state, correction math or runtime caller. */
internal object PositionTemporalPairing {
	private fun reject(reason: PositionTemporalPairingRejectionReason) = PositionTemporalPairingResult.Rejected(reason)

	fun check(
		input: PositionCorrectionInput,
		expectedTeacherEpoch: PositionTeacherEpoch,
		policy: PositionTemporalPairingPolicy,
	): PositionTemporalPairingResult {
		val structural = PositionCorrectionTeacherEligibility.check(input)
		if (!structural.eligibleForPairing) return PositionTemporalPairingResult.Rejected(
			PositionTemporalPairingRejectionReason.STRUCTURAL_INELIGIBLE, structural.reason)

		val teacherEpoch = PositionTeacherEpoch.from(input)
			?: return reject(PositionTemporalPairingRejectionReason.TEACHER_EPOCH_UNAVAILABLE)
		if (teacherEpoch != expectedTeacherEpoch) return reject(PositionTemporalPairingRejectionReason.TEACHER_EPOCH_MISMATCH)
		// Preflight guarantees both provenances, nonnegative now and current expected prediction epoch.
		// Retain explicit context/future guards here as the temporal boundary's safety conditions.
		val prediction = input.prediction
		val provenance = prediction.provenance!!
		val predictionEpoch = provenance.epoch
		if (predictionEpoch != input.expectedPredictionEpoch)
			return reject(PositionTemporalPairingRejectionReason.PREDICTION_EPOCH_MISMATCH)
		if (teacherEpoch.coordinateSpace != input.expectedSpace || prediction.space != input.expectedSpace ||
			predictionEpoch.coordinateSpace != input.expectedSpace)
			return reject(PositionTemporalPairingRejectionReason.SPACE_MISMATCH)
		if (teacherEpoch.assignmentGeneration != input.assignmentGeneration ||
			predictionEpoch.assignmentGeneration != input.assignmentGeneration)
			return reject(PositionTemporalPairingRejectionReason.ASSIGNMENT_GENERATION_MISMATCH)

		val teacherAt = input.mainTeacher.provenance.sampleAtNanos
		val earliest = provenance.inputEarliestAtNanos
		val latest = provenance.inputLatestAtNanos
		val generated = provenance.generatedAtNanos
		val now = input.nowNanos
		if (teacherAt > now) return reject(PositionTemporalPairingRejectionReason.FUTURE_TEACHER_SAMPLE)
		if (earliest > now || latest > now) return reject(PositionTemporalPairingRejectionReason.FUTURE_PREDICTION_INPUT)
		if (generated > now) return reject(PositionTemporalPairingRejectionReason.FUTURE_PREDICTION_GENERATION)

		// Constructors/preflight and future guards establish 0 <= each timestamp <= now <= MAX.
		// Ordered nonnegative subtraction is therefore exact, including [0, Long.MAX_VALUE].
		if (now - teacherAt > policy.maxTeacherAgeNanos) return reject(PositionTemporalPairingRejectionReason.TEACHER_STALE)
		if (now - latest > policy.maxPredictionInputAgeNanos) return reject(PositionTemporalPairingRejectionReason.PREDICTION_INPUT_STALE)
		if (now - generated > policy.maxPredictionGenerationAgeNanos)
			return reject(PositionTemporalPairingRejectionReason.PREDICTION_GENERATION_STALE)
		val distance = when {
			teacherAt < earliest -> earliest - teacherAt
			teacherAt > latest -> teacherAt - latest
			else -> 0L
		}
		if (distance > policy.maxTeacherToPredictionInputSkewNanos)
			return reject(PositionTemporalPairingRejectionReason.PAIRING_SKEW_EXCEEDED)
		return PositionTemporalPairingResult.Pairable(teacherAt, earliest, latest, distance, teacherEpoch, predictionEpoch)
	}
}
