package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Optional saved geometry/numerical overrides, not HIL tuning or usage permission.
 * Null in MonakaConfiguration uses normal runtime Identity geometry/default tuning.
 * Stored identities bind only the corresponding geometric override, never ordinary Pose admission.
 */
data class PositionCorrectionConfig(
	val rawImuSpace: PositionCorrectionRawImuSpaceConfig,
	val mainMountCalibration: PositionCorrectionMainMountCalibrationConfig,
	val fixedCalibration: PositionCorrectionFixedCalibrationConfig,
	val predictorPolicy: PositionCorrectionPredictorPolicyConfig,
	val pairingPolicy: PositionCorrectionPairingPolicyConfig,
	val learningTuning: PositionCorrectionLearningTuningConfig,
	val reacquisitionTuning: PositionCorrectionReacquisitionTuningConfig,
) {
	internal fun validateBinding(space: CoordinateSpace, @Suppress("UNUSED_PARAMETER") assignment: MainTrackerAssignment?) {
		require(rawImuSpace.space.convention == space.convention) { "Raw IMU convention is unsupported" }
		// Saved geometric source bindings are checked where that override is used.
		// Changing ordinary assignments cannot revoke the rest of the configuration.
	}

	/** Pure materialization; no live inputs, clock, assignment acquisition, learner or writeback. */
	internal fun toFoundation() = ConfiguredPositionCorrectionFoundation(
		rawImuSpace.toBinding(),
		mainMountCalibration.toSnapshot(),
		fixedCalibration.toSnapshot(),
		predictorPolicy.toRuntime(),
		pairingPolicy.toRuntime(),
		learningTuning.toRuntime(),
		reacquisitionTuning.toRuntime(),
	)
}

data class PositionCorrectionRawImuSpaceConfig(
	val sourceId: String,
	val space: CoordinateSpace,
	val operatorConfirmed: Boolean,
) {
	init {
		require(sourceId.isNotBlank()) { "Raw IMU source identity must be present" }
		require(space.id.isNotBlank() && space.revision in 0..4294967295L && space.convention == "rh_y_up_neg_z_forward")
	}
	internal fun toBinding() = SlimeRawImuCoordinateSpaceBinding(sourceId, space, operatorConfirmed)
}

/** Metres from raw tracker origin to HIP_CENTER, in tracker-local axes. Session changes on remount. */
data class PositionCorrectionMainMountCalibrationConfig(
	val calibrationId: String,
	val sessionEpoch: String,
	val sourceId: String,
	val trackerToHipCenterLocalOffsetMeters: Vector3,
) {
	init {
		toSnapshot()
	}
	internal fun toSnapshot(): MainTrackerMountCalibrationSnapshot {
		val result = MainTrackerMountCalibrationSnapshot.create(
			calibrationId,
			sessionEpoch,
			sourceId,
			trackerToHipCenterLocalOffsetMeters,
		)
		require(result is MainTrackerMountCalibrationSnapshotResult.Available) { "Invalid Main mount calibration: $result" }
		return result.snapshot
	}
}

/** Explicit HMD -> HEAD anchor rigid relation. No automatic HMD/body-model rebinding.
 * Session identifies fit/recalibration or loaded session, never a sample/update counter.
 * The factory alone owns normalization, canonical sign and calibration epoch generation.
 */
data class PositionCorrectionFixedCalibrationConfig(
	val calibrationId: String,
	val sessionEpoch: String,
	val hmdSourceId: String,
	val bodyModelId: String,
	val hmdToHeadAnchorLocalOffsetMeters: Vector3,
	val hmdToHeadAnchorOrientationWxyz: Quaternion,
) {
	init {
		toSnapshot()
	}
	internal fun toSnapshot(): PredictorFixedCalibrationSnapshot {
		val result = PredictorFixedCalibrationSnapshot.create(
			calibrationId,
			sessionEpoch,
			hmdSourceId,
			bodyModelId,
			hmdToHeadAnchorLocalOffsetMeters,
			hmdToHeadAnchorOrientationWxyz,
		)
		require(result is PredictorFixedCalibrationSnapshotResult.Available) { "Invalid fixed calibration: $result" }
		return result.snapshot
	}
}

data class PositionCorrectionPredictorPolicyConfig(
	val maxInputSkewNanos: Long,
	val maxHmdSampleAgeNanos: Long,
	val maxImuSampleAgeNanos: Long,
) {
	init {
		toRuntime()
	}
	internal fun toRuntime() = MainDecoupledHipPredictorPolicy(
		maxInputSkewNanos,
		maxHmdSampleAgeNanos,
		maxImuSampleAgeNanos,
	)
}

data class PositionCorrectionPairingPolicyConfig(
	val maxTeacherToPredictionInputSkewNanos: Long,
	val maxTeacherAgeNanos: Long,
	val maxPredictionInputAgeNanos: Long,
	val maxPredictionGenerationAgeNanos: Long,
) {
	init {
		toRuntime()
	}
	internal fun toRuntime() = PositionTemporalPairingPolicy(
		maxTeacherToPredictionInputSkewNanos,
		maxTeacherAgeNanos,
		maxPredictionInputAgeNanos,
		maxPredictionGenerationAgeNanos,
	)
}

data class PositionCorrectionLearningTuningConfig(
	val trackingTauSeconds: Double,
	val recoveryTauSeconds: Double,
	val maxResidualMeters: Double,
	val maxCorrectionMagnitudeMeters: Double,
	val maxCorrectionRateMetersPerSecond: Double,
	val maxUpdateStepMeters: Double,
	val maxLearningDtNanos: Long,
	val holdNanos: Long,
	val decayTauSeconds: Double,
	val maxDecayRateMetersPerSecond: Double,
	val recoveryResidualMeters: Double,
	val recoveryStableNanos: Long,
	val recoverySamples: Int,
	val zeroEpsilonMeters: Double,
) {
	init {
		toRuntime()
	}
	internal fun toRuntime() = PositionCorrectionTuning(
		trackingTauSeconds, recoveryTauSeconds, maxResidualMeters, maxCorrectionMagnitudeMeters, maxCorrectionRateMetersPerSecond, maxUpdateStepMeters, maxLearningDtNanos, holdNanos, decayTauSeconds, maxDecayRateMetersPerSecond, recoveryResidualMeters, recoveryStableNanos, recoverySamples, zeroEpsilonMeters,
	)
}

data class PositionCorrectionReacquisitionTuningConfig(
	val reacquireDurationNanos: Long,
) {
	init {
		toRuntime()
	}
	internal fun toRuntime() = PositionCorrectionReacquisitionTuning(
		reacquireDurationNanos,
	)
}

/** Validated immutable values for future adapters; no production wiring or source trust evidence. */
internal data class ConfiguredPositionCorrectionFoundation(
	val rawImuBinding: SlimeRawImuCoordinateSpaceBinding,
	val mainMountCalibration: MainTrackerMountCalibrationSnapshot,
	val fixedCalibration: PredictorFixedCalibrationSnapshot,
	val predictorPolicy: MainDecoupledHipPredictorPolicy,
	val pairingPolicy: PositionTemporalPairingPolicy,
	val learningTuning: PositionCorrectionTuning,
	val reacquisitionTuning: PositionCorrectionReacquisitionTuning,
)
