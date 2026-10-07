package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Vector3

/** Immutable world-space comparison snapshot, not learning, correction or IK authority.
 * Physical teacher time, prediction support, generation and evaluation remain separate facts.
 * Main-derived measurement must never feed back into Main-decoupled predictor inputs.
 */
internal data class PositionErrorSample(
	val target: TrackerPosition,
	val bodyReference: PositionBodyReference,
	val coordinateSpace: CoordinateSpace,
	val teacherPosition: Vector3,
	val predictionPosition: Vector3,
	val errorWorld: Vector3,
	val teacherSequence: Long,
	/** Caller invocation only, never physical dedupe authority. */
	val predictionSequence: Long,
	val predictionHmdSequence: Long,
	val predictionHmdSampleAtNanos: Long,
	val predictionImuSequence: Long,
	val predictionImuSampleAtNanos: Long,
	val teacherSampleAtNanos: Long,
	val predictionInputEarliestAtNanos: Long,
	val predictionInputLatestAtNanos: Long,
	val predictionGeneratedAtNanos: Long,
	val evaluatedAtNanos: Long,
	val teacherToPredictionInputDistanceNanos: Long,
	val teacherEpoch: PositionTeacherEpoch,
	val predictionEpoch: PositionPredictionEpoch,
	val assignmentGeneration: Long,
)

internal enum class PositionErrorMeasurementRejectionReason { PAIRING_REJECTED, ERROR_NONFINITE }

internal sealed interface PositionErrorMeasurementResult {
	/** Measurement validity only; no residual/outlier or learning policy is applied. */
	data class Measured(val sample: PositionErrorSample) : PositionErrorMeasurementResult

	data class Rejected(
		val reason: PositionErrorMeasurementRejectionReason,
		val pairingReason: PositionTemporalPairingRejectionReason? = null,
		val structuralReason: String? = null,
	) : PositionErrorMeasurementResult
}

/** Pure dormant measurement boundary. Pairability is owned solely by PositionTemporalPairing.
 * No detached Pairable input, clock, sequence allocator, dedupe, state or runtime connection.
 */
internal object PositionErrorMeasurement {
	fun evaluate(
		input: PositionCorrectionInput,
		expectedTeacherEpoch: PositionTeacherEpoch,
		pairingPolicy: PositionTemporalPairingPolicy,
	): PositionErrorMeasurementResult {
		val paired = when (val result = PositionTemporalPairing.check(input, expectedTeacherEpoch, pairingPolicy)) {
			is PositionTemporalPairingResult.Rejected -> return PositionErrorMeasurementResult.Rejected(
				PositionErrorMeasurementRejectionReason.PAIRING_REJECTED, result.reason, result.structuralReason)
			is PositionTemporalPairingResult.Pairable -> result
		}
		// The same immutable input that passed real pairing supplies exact samples and numeric snapshots.
		// Pairing's structural preflight guarantees finite sources and both provenances.
		val teacherSource = input.mainTeacher.position
		val predictionSource = input.prediction.position!!
		val teacher = Vector3(teacherSource.x, teacherSource.y, teacherSource.z)
		val prediction = Vector3(predictionSource.x, predictionSource.y, predictionSource.z)
		val error = Vector3(teacher.x - prediction.x, teacher.y - prediction.y, teacher.z - prediction.z)
		// Finite sources can overflow subtraction. Fail closed; no clamp or signed-zero rewrite.
		if (!error.x.isFinite() || !error.y.isFinite() || !error.z.isFinite())
			return PositionErrorMeasurementResult.Rejected(PositionErrorMeasurementRejectionReason.ERROR_NONFINITE)
		val provenance = input.prediction.provenance!!
		return PositionErrorMeasurementResult.Measured(PositionErrorSample(
			target = TrackerPosition.HIP,
			bodyReference = PositionBodyReference.HIP_CENTER,
			coordinateSpace = input.expectedSpace,
			teacherPosition = teacher,
			predictionPosition = prediction,
			errorWorld = error,
			teacherSequence = input.mainTeacher.provenance.sequence,
			predictionSequence = provenance.predictionSequence,
			predictionHmdSequence = provenance.inputHmdSequence,
			predictionHmdSampleAtNanos = provenance.inputHmdSampleAtNanos,
			predictionImuSequence = provenance.inputImuSequence,
			predictionImuSampleAtNanos = provenance.inputImuSampleAtNanos,
			teacherSampleAtNanos = paired.teacherSampleAtNanos,
			predictionInputEarliestAtNanos = paired.predictionInputEarliestAtNanos,
			predictionInputLatestAtNanos = paired.predictionInputLatestAtNanos,
			predictionGeneratedAtNanos = provenance.generatedAtNanos,
			evaluatedAtNanos = input.nowNanos,
			teacherToPredictionInputDistanceNanos = paired.teacherToPredictionInputDistanceNanos,
			teacherEpoch = paired.teacherEpoch,
			predictionEpoch = paired.predictionEpoch,
			assignmentGeneration = input.assignmentGeneration,
		))
	}
}
