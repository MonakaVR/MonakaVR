package dev.monaka.tracking

import com.fasterxml.jackson.databind.JsonNode
import dev.monaka.protocol.v2.CoordinateSpace
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Local schema v3 only. Strict at every Position Correction object; no missing-value inference. */
internal object PositionCorrectionConfigJson {
    private fun objectFields(node: JsonNode?, fields: Set<String>): JsonNode {
        require(node?.isObject == true && node.fieldNames().asSequence().toSet() == fields) {
            "Position correction requires exactly these fields: $fields"
        }
        return node
    }
    private fun text(node: JsonNode, name: String): String {
        val value = node[name]
        require(value?.isTextual == true && value.textValue().isNotBlank()) { "Missing/invalid $name" }
        return value.textValue()
    }
    private fun long(node: JsonNode, name: String): Long {
        val value = node[name]
        require(value?.isIntegralNumber == true && value.canConvertToLong()) { "$name must be a representable integer" }
        return value.longValue()
    }
    private fun int(node: JsonNode, name: String): Int {
        val value = node[name]
        require(value?.isIntegralNumber == true && value.canConvertToInt()) { "$name must be a representable integer" }
        return value.intValue()
    }
    private fun double(node: JsonNode, name: String): Double {
        val value = node[name]
        require(value?.isNumber == true && value.doubleValue().isFinite()) { "$name must be finite" }
        return value.doubleValue()
    }
    private fun floats(node: JsonNode, name: String, size: Int): List<Float> {
        val value = node[name]
        require(value?.isArray == true && value.size() == size) { "$name requires $size numbers" }
        return value.map {
            require(it.isNumber && it.doubleValue().isFinite() && it.floatValue().isFinite() &&
                (it.doubleValue() == 0.0 || it.floatValue() != 0f)) { "$name must fit finite Float without underflow to zero" }
            it.floatValue()
        }
    }
    private fun vector(node: JsonNode, name: String): Vector3 {
        val v = floats(node, name, 3)
        return Vector3(v[0], v[1], v[2])
    }
    private fun quaternion(node: JsonNode, name: String): Quaternion {
        val q = floats(node, name, 4)
        return Quaternion(q[0], q[1], q[2], q[3])
    }
    private fun space(node: JsonNode): CoordinateSpace {
        objectFields(node, setOf("id", "revision", "convention"))
        return CoordinateSpace(text(node, "id"), text(node, "convention"), long(node, "revision"))
    }

    fun read(node: JsonNode): PositionCorrectionConfig? {
        require(node.isObject && node["enabled"]?.isBoolean == true) { "positionCorrection.enabled must be explicit boolean" }
        if (!node["enabled"].booleanValue()) {
            objectFields(node, setOf("enabled"))
            return null
        }
        objectFields(node, setOf("enabled", "rawImuSpace", "mainMountCalibration", "predictorFixedCalibration", "predictorPolicy", "pairingPolicy", "learningTuning", "reacquisitionTuning"))
        val rawImuSpace = objectFields(node["rawImuSpace"], setOf("sourceId", "confirmed", "space"))
        val mainMountCalibration = objectFields(node["mainMountCalibration"], setOf("calibrationId", "sessionEpoch", "sourceId", "trackerToHipCenterLocalOffsetMeters"))
        val predictorFixedCalibration = objectFields(node["predictorFixedCalibration"], setOf("calibrationId", "sessionEpoch", "hmdSourceId", "bodyModelId", "hmdToHeadAnchorLocalOffsetMeters", "hmdToHeadAnchorOrientationWxyz"))
        val predictorPolicy = objectFields(node["predictorPolicy"], setOf("maxInputSkewNanos", "maxHmdSampleAgeNanos", "maxImuSampleAgeNanos"))
        val pairingPolicy = objectFields(node["pairingPolicy"], setOf("maxTeacherToPredictionInputSkewNanos", "maxTeacherAgeNanos", "maxPredictionInputAgeNanos", "maxPredictionGenerationAgeNanos"))
        val learningTuning = objectFields(node["learningTuning"], setOf("trackingTauSeconds", "recoveryTauSeconds", "maxResidualMeters", "maxCorrectionMagnitudeMeters", "maxCorrectionRateMetersPerSecond", "maxUpdateStepMeters", "maxLearningDtNanos", "holdNanos", "decayTauSeconds", "maxDecayRateMetersPerSecond", "recoveryResidualMeters", "recoveryStableNanos", "recoverySamples", "zeroEpsilonMeters"))
        val reacquisitionTuning = objectFields(node["reacquisitionTuning"], setOf("reacquireDurationNanos"))
        require(rawImuSpace["confirmed"].isBoolean && rawImuSpace["confirmed"].booleanValue()) { "Raw IMU confirmation must be true" }
        return PositionCorrectionConfig(
            PositionCorrectionRawImuSpaceConfig(text(rawImuSpace, "sourceId"), space(rawImuSpace["space"]), rawImuSpace["confirmed"].booleanValue()),
            PositionCorrectionMainMountCalibrationConfig(text(mainMountCalibration, "calibrationId"), text(mainMountCalibration, "sessionEpoch"), text(mainMountCalibration, "sourceId"), vector(mainMountCalibration, "trackerToHipCenterLocalOffsetMeters")),
            PositionCorrectionFixedCalibrationConfig(text(predictorFixedCalibration, "calibrationId"), text(predictorFixedCalibration, "sessionEpoch"), text(predictorFixedCalibration, "hmdSourceId"), text(predictorFixedCalibration, "bodyModelId"), vector(predictorFixedCalibration, "hmdToHeadAnchorLocalOffsetMeters"), quaternion(predictorFixedCalibration, "hmdToHeadAnchorOrientationWxyz")),
            PositionCorrectionPredictorPolicyConfig(long(predictorPolicy, "maxInputSkewNanos"), long(predictorPolicy, "maxHmdSampleAgeNanos"), long(predictorPolicy, "maxImuSampleAgeNanos")),
            PositionCorrectionPairingPolicyConfig(long(pairingPolicy, "maxTeacherToPredictionInputSkewNanos"), long(pairingPolicy, "maxTeacherAgeNanos"), long(pairingPolicy, "maxPredictionInputAgeNanos"), long(pairingPolicy, "maxPredictionGenerationAgeNanos")),
            PositionCorrectionLearningTuningConfig(double(learningTuning, "trackingTauSeconds"), double(learningTuning, "recoveryTauSeconds"), double(learningTuning, "maxResidualMeters"), double(learningTuning, "maxCorrectionMagnitudeMeters"), double(learningTuning, "maxCorrectionRateMetersPerSecond"), double(learningTuning, "maxUpdateStepMeters"), long(learningTuning, "maxLearningDtNanos"), long(learningTuning, "holdNanos"), double(learningTuning, "decayTauSeconds"), double(learningTuning, "maxDecayRateMetersPerSecond"), double(learningTuning, "recoveryResidualMeters"), long(learningTuning, "recoveryStableNanos"), int(learningTuning, "recoverySamples"), double(learningTuning, "zeroEpsilonMeters")),
            PositionCorrectionReacquisitionTuningConfig(long(reacquisitionTuning, "reacquireDurationNanos")),
        )
    }

    fun write(config: PositionCorrectionConfig): Map<String, Any> = with(config) {
        mapOf(
            "enabled" to true,
            "rawImuSpace" to with(rawImuSpace) { mapOf(
                "sourceId" to sourceId,
                "confirmed" to operatorConfirmed,
                "space" to mapOf("id" to space.id, "revision" to space.revision, "convention" to space.convention),
            ) },
            "mainMountCalibration" to with(mainMountCalibration) { mapOf(
                "calibrationId" to calibrationId,
                "sessionEpoch" to sessionEpoch,
                "sourceId" to sourceId,
                "trackerToHipCenterLocalOffsetMeters" to listOf(trackerToHipCenterLocalOffsetMeters.x, trackerToHipCenterLocalOffsetMeters.y, trackerToHipCenterLocalOffsetMeters.z),
            ) },
            "predictorFixedCalibration" to with(fixedCalibration) { mapOf(
                "calibrationId" to calibrationId,
                "sessionEpoch" to sessionEpoch,
                "hmdSourceId" to hmdSourceId,
                "bodyModelId" to bodyModelId,
                "hmdToHeadAnchorLocalOffsetMeters" to listOf(hmdToHeadAnchorLocalOffsetMeters.x, hmdToHeadAnchorLocalOffsetMeters.y, hmdToHeadAnchorLocalOffsetMeters.z),
                "hmdToHeadAnchorOrientationWxyz" to listOf(hmdToHeadAnchorOrientationWxyz.w, hmdToHeadAnchorOrientationWxyz.x, hmdToHeadAnchorOrientationWxyz.y, hmdToHeadAnchorOrientationWxyz.z),
            ) },
            "predictorPolicy" to with(predictorPolicy) { mapOf(
                "maxInputSkewNanos" to maxInputSkewNanos,
                "maxHmdSampleAgeNanos" to maxHmdSampleAgeNanos,
                "maxImuSampleAgeNanos" to maxImuSampleAgeNanos,
            ) },
            "pairingPolicy" to with(pairingPolicy) { mapOf(
                "maxTeacherToPredictionInputSkewNanos" to maxTeacherToPredictionInputSkewNanos,
                "maxTeacherAgeNanos" to maxTeacherAgeNanos,
                "maxPredictionInputAgeNanos" to maxPredictionInputAgeNanos,
                "maxPredictionGenerationAgeNanos" to maxPredictionGenerationAgeNanos,
            ) },
            "learningTuning" to with(learningTuning) { mapOf(
                "trackingTauSeconds" to trackingTauSeconds,
                "recoveryTauSeconds" to recoveryTauSeconds,
                "maxResidualMeters" to maxResidualMeters,
                "maxCorrectionMagnitudeMeters" to maxCorrectionMagnitudeMeters,
                "maxCorrectionRateMetersPerSecond" to maxCorrectionRateMetersPerSecond,
                "maxUpdateStepMeters" to maxUpdateStepMeters,
                "maxLearningDtNanos" to maxLearningDtNanos,
                "holdNanos" to holdNanos,
                "decayTauSeconds" to decayTauSeconds,
                "maxDecayRateMetersPerSecond" to maxDecayRateMetersPerSecond,
                "recoveryResidualMeters" to recoveryResidualMeters,
                "recoveryStableNanos" to recoveryStableNanos,
                "recoverySamples" to recoverySamples,
                "zeroEpsilonMeters" to zeroEpsilonMeters,
            ) },
            "reacquisitionTuning" to with(reacquisitionTuning) { mapOf(
                "reacquireDurationNanos" to reacquireDurationNanos,
            ) },
        )
    }
}
