package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition

internal enum class PositionCorrectionProductionRawImuRejection {
	HIP_ASSIGNMENT_UNAVAILABLE, FALLBACK_SOURCE_CHANGED_FROM_CONFIG, FALLBACK_NOT_SLIME,
	FALLBACK_TRACKER_NOT_FOUND, FALLBACK_TRACKER_AMBIGUOUS, BOUNDARY_REJECTED,
}

internal sealed interface PositionCorrectionProductionRawImuResult {
	data class Available(val input: RawImuOrientationInput) : PositionCorrectionProductionRawImuResult
	data class Unavailable(val reason: PositionCorrectionProductionRawImuRejection,
		val boundaryReason: SlimeRawImuInputRejectionReason? = null) : PositionCorrectionProductionRawImuResult
}

internal enum class PositionCorrectionProductionTeacherRejection {
	HIP_ASSIGNMENT_UNAVAILABLE, MAIN_NOT_MTP, MAIN_SOURCE_CHANGED_FROM_CONFIG,
	RAW_OBSERVATION_UNAVAILABLE, RAW_OBSERVATION_SOURCE_MISMATCH, SOURCE_NOT_OWNED_BY_MTP,
	MTP_CONTEXT_UNAVAILABLE, MTP_CONTEXT_IDENTITY_MISMATCH, MTP_CONTEXT_SPACE_MISMATCH,
	MOUNT_CALIBRATION_SOURCE_MISMATCH, NORMALIZATION_REJECTED, TEACHER_CONTEXT_MISMATCH,
}

internal sealed interface PositionCorrectionProductionTeacherResult {
	data class Available(val value: PositionCorrectionTeacherTickValue) : PositionCorrectionProductionTeacherResult
	data class Unavailable(val reason: PositionCorrectionProductionTeacherRejection,
		val normalizationReason: MainHipCenterTeacherRejectionReason? = null) : PositionCorrectionProductionTeacherResult
}

internal enum class PositionCorrectionProductionFixedCalibrationRejection { BODY_MODEL_UNAVAILABLE, BODY_MODEL_ID_MISMATCH }

internal sealed interface PositionCorrectionProductionFixedCalibrationResult {
	data class Available(val calibration: PredictorFixedCalibrationSnapshot) : PositionCorrectionProductionFixedCalibrationResult
	data class Unavailable(val reason: PositionCorrectionProductionFixedCalibrationRejection,
		val bodyModelReason: String? = null) : PositionCorrectionProductionFixedCalibrationResult
}

/** Immutable component facts. Deliberately has no HMD input or complete predictor sources. */
internal data class PositionCorrectionNonHmdProductionTick(
	val tickSequence: Long,
	val nowNanos: Long,
	val assignmentGeneration: Long,
	val paused: Boolean,
	val rawImu: PositionCorrectionProductionRawImuResult,
	val teacher: PositionCorrectionProductionTeacherResult,
	val bodyModel: HipBodyModelSnapshotResult,
	val fixedCalibration: PositionCorrectionProductionFixedCalibrationResult,
)

internal enum class PositionCorrectionNonHmdCaptureRejection { TICK_SEQUENCE_MISMATCH, TICK_TIME_MISMATCH, ASSIGNMENT_MISMATCH }

internal sealed interface PositionCorrectionNonHmdCaptureResult {
	data class Available(val tick: PositionCorrectionNonHmdProductionTick) : PositionCorrectionNonHmdCaptureResult
	data class Rejected(val reason: PositionCorrectionNonHmdCaptureRejection) : PositionCorrectionNonHmdCaptureResult
}

/** Dormant core seam. Providers are snapshotted once, after fatal coherence checks; no runtime side effects. */
internal class PositionCorrectionNonHmdProductionAdapter(
	private val foundation: ConfiguredPositionCorrectionFoundation,
	private val trackers: () -> Iterable<Tracker>,
	private val bodyModelSource: () -> HipBodyModelSnapshotResult,
	private val rawImuBoundary: SlimeRawImuProductionBoundary = SlimeRawImuProductionBoundary(),
) {
	fun capture(tick: MonakaResolvedTickSnapshot, sources: MonakaSourceTickSnapshot): PositionCorrectionNonHmdCaptureResult {
		if (tick.tickSequence != sources.tickSequence)
			return PositionCorrectionNonHmdCaptureResult.Rejected(PositionCorrectionNonHmdCaptureRejection.TICK_SEQUENCE_MISMATCH)
		if (tick.nowNanos != sources.nowNanos)
			return PositionCorrectionNonHmdCaptureResult.Rejected(PositionCorrectionNonHmdCaptureRejection.TICK_TIME_MISMATCH)
		if (tick.assignment != sources.assignment)
			return PositionCorrectionNonHmdCaptureResult.Rejected(PositionCorrectionNonHmdCaptureRejection.ASSIGNMENT_MISMATCH)
		val body = bodyModelSource()
		val physical = trackers().toList()
		val fixed = when (body) {
			is HipBodyModelSnapshotResult.Unavailable -> PositionCorrectionProductionFixedCalibrationResult.Unavailable(
				PositionCorrectionProductionFixedCalibrationRejection.BODY_MODEL_UNAVAILABLE, body.reason)
			is HipBodyModelSnapshotResult.Available -> if (foundation.fixedCalibration.bodyModelId == body.snapshot.identity.modelId)
				PositionCorrectionProductionFixedCalibrationResult.Available(foundation.fixedCalibration)
			else PositionCorrectionProductionFixedCalibrationResult.Unavailable(PositionCorrectionProductionFixedCalibrationRejection.BODY_MODEL_ID_MISMATCH)
		}
		return PositionCorrectionNonHmdCaptureResult.Available(PositionCorrectionNonHmdProductionTick(
			tick.tickSequence, tick.nowNanos, tick.assignment.generation, tick.paused,
			captureRawImu(tick, physical), captureTeacher(tick, sources), body, fixed))
	}

	private fun captureRawImu(tick: MonakaResolvedTickSnapshot, physical: List<Tracker>): PositionCorrectionProductionRawImuResult {
		fun reject(reason: PositionCorrectionProductionRawImuRejection) = PositionCorrectionProductionRawImuResult.Unavailable(reason)
		val hip = tick.assignment.targets[TrackerPosition.HIP]
			?: return reject(PositionCorrectionProductionRawImuRejection.HIP_ASSIGNMENT_UNAVAILABLE)
		val fallback = hip.rotationFallbackTracker
		if (fallback == null || fallback.observationId != foundation.rawImuBinding.sourceId)
			return reject(PositionCorrectionProductionRawImuRejection.FALLBACK_SOURCE_CHANGED_FROM_CONFIG)
		if (fallback.mtp != null || !fallback.observationId.startsWith("slime:"))
			return reject(PositionCorrectionProductionRawImuRejection.FALLBACK_NOT_SLIME)
		val matches = physical.filter { it.name == fallback.observationId.removePrefix("slime:") }
		if (matches.isEmpty()) return reject(PositionCorrectionProductionRawImuRejection.FALLBACK_TRACKER_NOT_FOUND)
		if (matches.size != 1) return reject(PositionCorrectionProductionRawImuRejection.FALLBACK_TRACKER_AMBIGUOUS)
		return when (val raw = rawImuBoundary.adaptAtTick(matches.single(), tick.nowNanos,
			tick.trackerReceiptCutoffSystemNanos, foundation.rawImuBinding)) {
			is SlimeRawImuInputResult.Available -> PositionCorrectionProductionRawImuResult.Available(raw.input)
			is SlimeRawImuInputResult.Unavailable -> PositionCorrectionProductionRawImuResult.Unavailable(
				PositionCorrectionProductionRawImuRejection.BOUNDARY_REJECTED, raw.reason)
		}
	}

	private fun captureTeacher(tick: MonakaResolvedTickSnapshot, sources: MonakaSourceTickSnapshot): PositionCorrectionProductionTeacherResult {
		fun reject(reason: PositionCorrectionProductionTeacherRejection) = PositionCorrectionProductionTeacherResult.Unavailable(reason)
		val main = tick.assignment.targets[TrackerPosition.HIP]?.mainTracker
			?: return reject(PositionCorrectionProductionTeacherRejection.HIP_ASSIGNMENT_UNAVAILABLE)
		val logical = main.mtp ?: return reject(PositionCorrectionProductionTeacherRejection.MAIN_NOT_MTP)
		val mount = foundation.mainMountCalibration
		if (main.observationId != mount.sourceId)
			return reject(PositionCorrectionProductionTeacherRejection.MAIN_SOURCE_CHANGED_FROM_CONFIG)
		val observation = sources.observationsBySource[main.observationId]
			?: return reject(PositionCorrectionProductionTeacherRejection.RAW_OBSERVATION_UNAVAILABLE)
		if (observation.sourceId != main.observationId)
			return reject(PositionCorrectionProductionTeacherRejection.RAW_OBSERVATION_SOURCE_MISMATCH)
		if (sources.sourceOwners[main.observationId] != "mtp")
			return reject(PositionCorrectionProductionTeacherRejection.SOURCE_NOT_OWNED_BY_MTP)
		val context = sources.mtpContexts[logical]
			?: return reject(PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_UNAVAILABLE)
		if (context.logicalTracker != logical)
			return reject(PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_IDENTITY_MISMATCH)
		if (context.coordinateSpace != foundation.rawImuBinding.space)
			return reject(PositionCorrectionProductionTeacherRejection.MTP_CONTEXT_SPACE_MISMATCH)
		// Authority is established by typed identity + runner ownership + current backend context.
		val rawOrigin = RawSourceIdentity(main.observationId, RawSourceKind.RAW_BACKEND)
		val teacher = when (val normalized = MainTrackerMountToHipCenter.normalize(observation, rawOrigin, mount)) {
			is MainHipCenterTeacherResult.Available -> normalized.teacher
			is MainHipCenterTeacherResult.Unavailable -> return PositionCorrectionProductionTeacherResult.Unavailable(
				PositionCorrectionProductionTeacherRejection.NORMALIZATION_REJECTED, normalized.reason)
		}
		val expected = PositionTeacherEpoch(main.observationId, context.sourceEpoch, context.calibrationEpoch,
			context.mappingRevision, context.coordinateSpace, tick.assignment.generation,
			PositionBodyReference.HIP_CENTER, mount.identity)
		val p = teacher.provenance
		if (p.sourceEpoch != expected.sourceEpoch || p.calibrationEpoch != expected.calibrationEpoch ||
			p.mappingRevision != expected.mappingRevision || p.space != expected.coordinateSpace)
			return reject(PositionCorrectionProductionTeacherRejection.TEACHER_CONTEXT_MISMATCH)
		return PositionCorrectionProductionTeacherResult.Available(PositionCorrectionTeacherTickValue(teacher, expected))
	}
}
