package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Explicit operator/config inputs, not HIL tuned defaults. Null in MonakaConfiguration means opt-out.
 * Persistence DTOs do not confer live source authority or activate tracking. Every value is mandatory.
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
    internal fun validateBinding(space: CoordinateSpace, assignment: MainTrackerAssignment?) {
        require(rawImuSpace.space == space) { "Raw IMU space must match id, revision and convention exactly" }
        require(assignment != null && assignment.useAsIkConstraint && assignment.rotationFallbackTracker != null) {
            "Position correction requires HIP IK participation and explicit rotation fallback"
        }
        val fallback = assignment.rotationFallbackTracker
        require(fallback.mtp == null && fallback.observationId.startsWith("slime:")) {
            "Position correction requires an explicitly assigned physical Slime IMU fallback"
        }
        require(assignment.mainTracker.mtp != null) { "Position correction teacher requires raw MTP Main" }
        require(rawImuSpace.sourceId == fallback.observationId) { "Raw IMU confirmation belongs to another source" }
        require(mainMountCalibration.sourceId == assignment.mainTracker.observationId) { "Main mount belongs to another source" }
    }

    /** Pure materialization; no live inputs, clock, assignment acquisition, learner or writeback. */
    internal fun toFoundation() = ConfiguredPositionCorrectionFoundation(
        rawImuSpace.toBinding(), mainMountCalibration.toSnapshot(), fixedCalibration.toSnapshot(),
        predictorPolicy.toRuntime(), pairingPolicy.toRuntime(), learningTuning.toRuntime(), reacquisitionTuning.toRuntime(),
    )
}

data class PositionCorrectionRawImuSpaceConfig(
    val sourceId: String,
    val space: CoordinateSpace,
    val operatorConfirmed: Boolean,
) {
    init {
        require(sourceId.isNotBlank() && operatorConfirmed) { "Raw IMU space must be explicitly source-confirmed" }
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
    init { toSnapshot() }
    internal fun toSnapshot(): MainTrackerMountCalibrationSnapshot {
        val result = MainTrackerMountCalibrationSnapshot.create(calibrationId, sessionEpoch, sourceId,
            trackerToHipCenterLocalOffsetMeters)
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
    init { toSnapshot() }
    internal fun toSnapshot(): PredictorFixedCalibrationSnapshot {
        val result = PredictorFixedCalibrationSnapshot.create(calibrationId, sessionEpoch, hmdSourceId, bodyModelId,
            hmdToHeadAnchorLocalOffsetMeters, hmdToHeadAnchorOrientationWxyz)
        require(result is PredictorFixedCalibrationSnapshotResult.Available) { "Invalid fixed calibration: $result" }
        return result.snapshot
    }
}

data class PositionCorrectionPredictorPolicyConfig(
    val maxInputSkewNanos: Long,
    val maxHmdSampleAgeNanos: Long,
    val maxImuSampleAgeNanos: Long,
) {
    init { toRuntime() }
    internal fun toRuntime() = MainDecoupledHipPredictorPolicy(
        maxInputSkewNanos, maxHmdSampleAgeNanos, maxImuSampleAgeNanos
    )
}

data class PositionCorrectionPairingPolicyConfig(
    val maxTeacherToPredictionInputSkewNanos: Long,
    val maxTeacherAgeNanos: Long,
    val maxPredictionInputAgeNanos: Long,
    val maxPredictionGenerationAgeNanos: Long,
) {
    init { toRuntime() }
    internal fun toRuntime() = PositionTemporalPairingPolicy(
        maxTeacherToPredictionInputSkewNanos, maxTeacherAgeNanos, maxPredictionInputAgeNanos, maxPredictionGenerationAgeNanos
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
    init { toRuntime() }
    internal fun toRuntime() = PositionCorrectionTuning(
        trackingTauSeconds, recoveryTauSeconds, maxResidualMeters, maxCorrectionMagnitudeMeters, maxCorrectionRateMetersPerSecond, maxUpdateStepMeters, maxLearningDtNanos, holdNanos, decayTauSeconds, maxDecayRateMetersPerSecond, recoveryResidualMeters, recoveryStableNanos, recoverySamples, zeroEpsilonMeters
    )
}

data class PositionCorrectionReacquisitionTuningConfig(
    val reacquireDurationNanos: Long,
) {
    init { toRuntime() }
    internal fun toRuntime() = PositionCorrectionReacquisitionTuning(
        reacquireDurationNanos
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
