package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.util.Collections

/** Contract only. No Phase 2A implementation produces or consumes a prediction in the runtime. */
enum class PositionBodyReference { HIP_CENTER, TRACKER_MOUNT, UNKNOWN }
enum class PredictionValidity { AVAILABLE, UNAVAILABLE }
enum class RawSourceKind { RAW_HMD, RAW_IMU, RAW_BACKEND, COMPUTED_TRACKER, DERIVED_OUTPUT }

/** Capture at a trusted ingress boundary, before any solver/proxy/output transformation. */
data class RawSourceIdentity(
	val sourceId: String,
	val kind: RawSourceKind,
	val isComputed: Boolean = false,
	val isInternal: Boolean = false,
	val isHmd: Boolean = false,
) {
	init { require(sourceId.isNotBlank()) }
	private val excluded get() = FeedbackExclusion.isOutput(sourceId) || isInternal
	fun isRawHmd() = !excluded && kind == RawSourceKind.RAW_HMD && isHmd
	// SteamVR's external HMD Tracker may carry isComputed=true; kind must come from ingress, not role alone.
	fun isRawImu() = !excluded && kind == RawSourceKind.RAW_IMU && !isComputed && !isHmd
	fun isRawBackend() = !excluded && kind == RawSourceKind.RAW_BACKEND && !isComputed && !isHmd
}

/** These are copied values, never a mutable Tracker, HumanSkeleton or computed pose reference. */
data class RawHmdPoseInput(
	val source: RawSourceIdentity,
	val position: Vector3,
	val orientation: Quaternion,
	val space: CoordinateSpace,
	val provenance: ObservationSampleProvenance,
) {
	init {
		require(source.isRawHmd() && finite(position) && valid(orientation))
		require(provenance.space == space) { "Raw HMD space must be explicit" }
	}
}

data class RawImuOrientationInput(
	val source: RawSourceIdentity,
	val orientation: Quaternion,
	val space: CoordinateSpace,
	val provenance: ObservationSampleProvenance,
) {
	init {
		require(source.isRawImu() && valid(orientation))
		require(provenance.space == space) { "Raw IMU space must be explicit" }
	}
}

/** Opaque until an immutable body-dimension snapshot is proven safe to extract. */
data class BodyModelIdentity(val modelId: String, val epoch: String) {
	init { require(modelId.isNotBlank() && epoch.isNotBlank()) }
}
data class FixedCalibrationIdentity(val calibrationId: String, val epoch: String) {
	init { require(calibrationId.isNotBlank() && epoch.isNotBlank()) }
}

/** Assignment changes alter the input relation, even when all numeric values happen to match. */
data class PositionPredictionEpoch(
	val hmdSourceId: String,
	val hmdSourceEpoch: String,
	val hmdCalibrationEpoch: String,
	val hmdMappingRevision: Long?,
	val imuSourceId: String,
	val imuSourceEpoch: String,
	val imuCalibrationEpoch: String,
	val imuMappingRevision: Long?,
	val bodyModelEpoch: String,
	val fixedCalibrationEpoch: String,
	val coordinateSpace: CoordinateSpace,
	val assignmentGeneration: Long,
) {
	init {
		require(listOf(hmdSourceId, hmdSourceEpoch, hmdCalibrationEpoch, imuSourceId, imuSourceEpoch,
			imuCalibrationEpoch, bodyModelEpoch, fixedCalibrationEpoch).all(String::isNotBlank))
		require(assignmentGeneration >= 0)
	}
}

/** Input support window is in the Monaka local monotonic domain; generatedAt is not a sample time. */
data class PositionPredictionProvenance(
	val predictionSequence: Long,
	val generatedAtNanos: Long,
	val inputEarliestAtNanos: Long,
	val inputLatestAtNanos: Long,
	val epoch: PositionPredictionEpoch,
) {
	init {
		require(predictionSequence >= 0 && inputEarliestAtNanos >= 0)
		require(inputEarliestAtNanos <= inputLatestAtNanos && inputLatestAtNanos <= generatedAtNanos)
	}
}

/** A model prediction is derived data, never an independent absolute measurement. */
enum class PositionPredictionDependency {
	RAW_HMD, RAW_IMU, BODY_MODEL, FIXED_CALIBRATION, ROTATION_CORRECTION,
	MAIN_OBSERVATION, RESOLVED_MAIN, IK_CONSTRAINT, COMPUTED_TRACKER,
	BACKGROUND_IK, VISIBLE_OUTPUT, DIRECT_OUTPUT,
}

data class MainDecoupledHipInput(
	val rawHmd: RawHmdPoseInput,
	val rawImu: RawImuOrientationInput,
	val bodyModel: BodyModelIdentity,
	val fixedCalibration: FixedCalibrationIdentity,
	val space: CoordinateSpace,
	val assignmentGeneration: Long,
	val nowNanos: Long,
	val target: TrackerPosition = TrackerPosition.HIP,
) {
	init {
		require(target == TrackerPosition.HIP && assignmentGeneration >= 0 && nowNanos >= 0)
		require(rawHmd.space == space && rawImu.space == space)
		require(rawHmd.provenance.sampleAtNanos <= nowNanos && rawImu.provenance.sampleAtNanos <= nowNanos)
	}
	fun epoch() = PositionPredictionEpoch(
		hmdSourceId = rawHmd.source.sourceId,
		hmdSourceEpoch = rawHmd.provenance.sourceEpoch,
		hmdCalibrationEpoch = rawHmd.provenance.calibrationEpoch,
		hmdMappingRevision = rawHmd.provenance.mappingRevision,
		imuSourceId = rawImu.source.sourceId,
		imuSourceEpoch = rawImu.provenance.sourceEpoch,
		imuCalibrationEpoch = rawImu.provenance.calibrationEpoch,
		imuMappingRevision = rawImu.provenance.mappingRevision,
		bodyModelEpoch = bodyModel.epoch,
		fixedCalibrationEpoch = fixedCalibration.epoch,
		coordinateSpace = space,
		assignmentGeneration = assignmentGeneration,
	)
}

fun interface MainDecoupledHipPredictor {
	fun predict(input: MainDecoupledHipInput): PositionPrediction
}

/** Cannot be registered with ConstraintPipeline: it is deliberately not a PoseObservation. */
class PositionPrediction private constructor(
	val target: TrackerPosition,
	val position: Vector3?,
	val space: CoordinateSpace?,
	val bodyReference: PositionBodyReference?,
	val validity: PredictionValidity,
	val provenance: PositionPredictionProvenance?,
	val dependencies: Set<PositionPredictionDependency>,
) {
	companion object {
		fun available(target: TrackerPosition, position: Vector3, space: CoordinateSpace?,
			bodyReference: PositionBodyReference?, provenance: PositionPredictionProvenance?,
			dependencies: Set<PositionPredictionDependency>): PositionPrediction {
			require(finite(position) && space != null && bodyReference != null && bodyReference != PositionBodyReference.UNKNOWN)
			require(provenance != null && dependencies.isNotEmpty())
			return PositionPrediction(target, position, space, bodyReference, PredictionValidity.AVAILABLE,
				provenance, Collections.unmodifiableSet(dependencies.toSet()))
		}
		fun unavailable(target: TrackerPosition, space: CoordinateSpace? = null) = PositionPrediction(
			target, null, space, null, PredictionValidity.UNAVAILABLE, null, emptySet())
	}
}

data class PositionCorrectionInput(
	val rawMainPositionObservation: PoseObservation,
	val rawMainOrigin: RawSourceIdentity,
	val mainBodyReference: PositionBodyReference?,
	val prediction: PositionPrediction,
	val expectedSpace: CoordinateSpace,
	val expectedPredictionEpoch: PositionPredictionEpoch,
	val assignmentGeneration: Long,
	val nowNanos: Long,
)

data class PositionTeacherEligibility(val eligibleForPairing: Boolean, val reason: String)

/** Structural preflight only. Phase 2B must still define temporal pairing before learning. */
object PositionCorrectionTeacherEligibility {
	val forbiddenDependencies: Set<PositionPredictionDependency> = setOf(
		PositionPredictionDependency.MAIN_OBSERVATION, PositionPredictionDependency.RESOLVED_MAIN,
		PositionPredictionDependency.IK_CONSTRAINT, PositionPredictionDependency.COMPUTED_TRACKER,
		PositionPredictionDependency.BACKGROUND_IK, PositionPredictionDependency.VISIBLE_OUTPUT,
		PositionPredictionDependency.DIRECT_OUTPUT, PositionPredictionDependency.ROTATION_CORRECTION,
	)
	private val requiredDependencies = setOf(PositionPredictionDependency.RAW_HMD,
		PositionPredictionDependency.RAW_IMU, PositionPredictionDependency.BODY_MODEL,
		PositionPredictionDependency.FIXED_CALIBRATION)
	private fun reject(reason: String) = PositionTeacherEligibility(false, reason)
	// Equal TRACKER_MOUNT tags do not identify the same physical mounting point.
	private fun directlyComparable(main: PositionBodyReference?, prediction: PositionBodyReference?) =
		main == PositionBodyReference.HIP_CENTER && prediction == PositionBodyReference.HIP_CENTER

	fun check(prediction: PositionPrediction): PositionTeacherEligibility {
		if (prediction.validity != PredictionValidity.AVAILABLE || prediction.position == null) return reject("prediction_unavailable")
		if (!finite(prediction.position)) return reject("prediction_nonfinite")
		if (prediction.space == null) return reject("prediction_space_unknown")
		if (prediction.bodyReference == null || prediction.bodyReference == PositionBodyReference.UNKNOWN)
			return reject("prediction_body_reference_unknown")
		if (prediction.provenance == null) return reject("prediction_provenance_missing")
		if (prediction.provenance.epoch.coordinateSpace != prediction.space) return reject("prediction_space_epoch_mismatch")
		if (prediction.dependencies.any { it in forbiddenDependencies }) return reject("feedback_dependency")
		if (!prediction.dependencies.containsAll(requiredDependencies)) return reject("input_lineage_incomplete")
		return PositionTeacherEligibility(true, "eligible_for_pairing")
	}

	fun check(input: PositionCorrectionInput): PositionTeacherEligibility {
		val prediction = input.prediction
		val base = check(prediction)
		if (!base.eligibleForPairing) return base
		val main = input.rawMainPositionObservation
		if (!input.rawMainOrigin.isRawBackend() || input.rawMainOrigin.sourceId != main.sourceId)
			return reject("main_not_raw_backend")
		if (main.target != prediction.target || main.position == null || !main.positionQuality.usable || !finite(main.position))
			return reject("main_position_invalid")
		val mainProvenance = main.provenance ?: return reject("main_provenance_missing")
		if (mainProvenance.space != input.expectedSpace || prediction.space != input.expectedSpace)
			return reject("space_mismatch")
		if (input.mainBodyReference == null || input.mainBodyReference == PositionBodyReference.UNKNOWN)
			return reject("main_body_reference_unknown")
		if (input.mainBodyReference != prediction.bodyReference) return reject("body_reference_mismatch")
		if (!directlyComparable(input.mainBodyReference, prediction.bodyReference))
			return reject("body_reference_not_comparable")
		if (input.assignmentGeneration < 0 || input.expectedPredictionEpoch.coordinateSpace != input.expectedSpace ||
			input.assignmentGeneration != input.expectedPredictionEpoch.assignmentGeneration ||
			prediction.provenance!!.epoch != input.expectedPredictionEpoch) return reject("prediction_epoch_mismatch")
		if (input.nowNanos < 0 || mainProvenance.sampleAtNanos > input.nowNanos ||
			prediction.provenance.generatedAtNanos > input.nowNanos) return reject("future_sample")
		return base
	}
}

private fun finite(position: Vector3) = listOf(position.x, position.y, position.z).all(Float::isFinite)
private fun valid(orientation: Quaternion) = listOf(orientation.w, orientation.x, orientation.y, orientation.z)
	.all(Float::isFinite) && orientation.lenSq().isFinite() && orientation.lenSq() > 1e-10f
