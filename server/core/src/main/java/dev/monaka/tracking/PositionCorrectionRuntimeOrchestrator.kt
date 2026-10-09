package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import java.util.Collections

internal data class PositionCorrectionPredictorSources(
	val rawHmd: RawHmdPoseInput,
	val rawImu: RawImuOrientationInput,
	val bodyModel: HipBodyModelSnapshot,
	val fixedCalibration: PredictorFixedCalibrationSnapshot,
)

/** Expected epoch is caller context, independent of the supplied teacher sample. */
internal data class PositionCorrectionTeacherTickValue(
	val teacher: MainHipCenterPositionTeacher,
	val expectedEpoch: PositionTeacherEpoch,
)

/** Capture before processing. Neither caller-owned map can mutate these tick facts. */
internal class PositionCorrectionRuntimeTick(
	val tickSequence: Long,
	val nowNanos: Long,
	val resolvedAtNanos: Long,
	val expectedSpace: CoordinateSpace,
	assignment: TrackerBodyAssignments.Snapshot,
	resolvedConstraints: Map<TrackerPosition, EffectiveConstraint>,
	val predictorSources: PositionCorrectionPredictorSources?,
	val teacher: PositionCorrectionTeacherTickValue?,
) {
	val assignment = assignment.copy(targets = immutableMap(assignment.targets))
	val resolvedConstraints = immutableMap(resolvedConstraints)
}

internal enum class PositionCorrectionAssemblyFailure {
	SOURCES_ABSENT, HMD_SPACE_MISMATCH, IMU_SPACE_MISMATCH, FUTURE_HMD_SAMPLE,
	FUTURE_IMU_SAMPLE, FIXED_HMD_SOURCE_MISMATCH, FIXED_BODY_MODEL_MISMATCH,
	COMMON_WORLD_EPOCH_MISMATCH,
}

internal sealed interface PositionCorrectionPredictorAssemblyResult {
	data class Available(val input: MainDecoupledHipInput) : PositionCorrectionPredictorAssemblyResult
	data class Unavailable(val reason: PositionCorrectionAssemblyFailure) : PositionCorrectionPredictorAssemblyResult
}

internal enum class PositionCorrectionRuntimeRejectionReason {
	TICK_SEQUENCE_INVALID, DUPLICATE_TICK, TICK_SEQUENCE_ROLLBACK, TIME_INVALID,
	TIME_ROLLBACK, RESOLVED_TIME_MISMATCH, ASSIGNMENT_GENERATION_INVALID,
}

internal data class PositionCorrectionRuntimeCallCounts(
	val predict: Int, val measure: Int, val observe: Int, val gap: Int,
	val application: Int, val mainProjection: Int, val continuity: Int, val writeback: Int,
)

/** Evidence only; never an input acquisition or prediction/teacher authority. */
internal sealed interface PositionCorrectionRuntimeTickResult {
	data class Rejected(val reason: PositionCorrectionRuntimeRejectionReason) : PositionCorrectionRuntimeTickResult
	data class Processed(
		val tickSequence: Long,
		val nowNanos: Long,
		val assembly: PositionCorrectionPredictorAssemblyResult,
		val prediction: PositionPrediction?,
		val measurement: PositionErrorMeasurementResult?,
		val learning: PositionCorrectionLearningResult,
		val application: PositionCorrectionApplicationResult?,
		val mainProjection: MainEffectiveHipTargetResult?,
		val continuity: PositionCorrectionSolverContinuityResult,
		val solverConstraints: Map<TrackerPosition, SolverEffectiveConstraint>,
		val writeback: Map<TrackerPosition, ConstraintIkWriteback.ApplyResult>,
		val calls: PositionCorrectionRuntimeCallCounts,
	) : PositionCorrectionRuntimeTickResult
}

/** Small test seam. Both operations must bind the same current solver boundary.
 * Implementations must preserve synchronous projection-to-commit calibration ownership.
 */
internal interface PositionCorrectionRuntimeSolverSink {
	fun projectMainEffectiveHipTarget(base: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot,
		nowNanos: Long): MainEffectiveHipTargetResult
	fun applySolver(constraints: Map<TrackerPosition, SolverEffectiveConstraint>,
		assignment: TrackerBodyAssignments.Snapshot): Map<TrackerPosition, ConstraintIkWriteback.ApplyResult>
}

private class ConstraintPositionCorrectionSink(private val writeback: ConstraintIkWriteback) : PositionCorrectionRuntimeSolverSink {
	override fun projectMainEffectiveHipTarget(base: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot,
		nowNanos: Long) = writeback.projectMainEffectiveHipTarget(base, assignment, nowNanos)
	override fun applySolver(constraints: Map<TrackerPosition, SolverEffectiveConstraint>,
		assignment: TrackerBodyAssignments.Snapshot) = writeback.applySolver(constraints, assignment)
}

/** Explicit opt-in, dormant, synchronous active-tick pipeline. Each instance exclusively
 * owns its learner and continuity controller. Caller supplies all policies and immutable facts,
 * and runs this on the solver's owning thread with no concurrent calibration mutation.
 * A passed tick is reserved before stages: exceptions propagate, never trigger same-tick retry.
 */
internal class PositionCorrectionRuntimeOrchestrator(
	private val predictor: MainDecoupledHipPredictor,
	private val pairingPolicy: PositionTemporalPairingPolicy,
	learningTuning: PositionCorrectionTuning,
	reacquisitionTuning: PositionCorrectionReacquisitionTuning,
	private val sink: PositionCorrectionRuntimeSolverSink,
) {
	constructor(predictorPolicy: MainDecoupledHipPredictorPolicy, pairingPolicy: PositionTemporalPairingPolicy,
		learningTuning: PositionCorrectionTuning, reacquisitionTuning: PositionCorrectionReacquisitionTuning,
		writeback: ConstraintIkWriteback) : this(PureMainDecoupledHipPredictor(predictorPolicy), pairingPolicy,
		learningTuning, reacquisitionTuning, ConstraintPositionCorrectionSink(writeback))

	private val learningLaw = PositionCorrectionLearningLaw(learningTuning)
	private val continuity = PositionCorrectionSolverContinuity(reacquisitionTuning)
	private var lastSequence: Long? = null
	private var lastNow: Long? = null

	fun process(tick: PositionCorrectionRuntimeTick): PositionCorrectionRuntimeTickResult {
		preflight(tick)?.let { return PositionCorrectionRuntimeTickResult.Rejected(it) }
		lastSequence = tick.tickSequence
		lastNow = tick.nowNanos
		val now = tick.nowNanos
		val assignment = tick.assignment
		val resolved = tick.resolvedConstraints
		val baseHip = resolved[TrackerPosition.HIP] ?: EffectiveConstraint(TrackerPosition.HIP)
		val assembly = assemble(tick)
		val prediction = (assembly as? PositionCorrectionPredictorAssemblyResult.Available)?.let {
			predictor.predict(it.input)
		}
		val epoch = prediction?.takeIf { it.validity == PredictionValidity.AVAILABLE }?.provenance?.epoch
		val measurement = if (tick.teacher != null && epoch != null) {
			PositionErrorMeasurement.evaluate(PositionCorrectionInput(tick.teacher.teacher, prediction,
				tick.expectedSpace, epoch, assignment.generation, now), tick.teacher.expectedEpoch, pairingPolicy)
		} else null
		// Exclusive branch: observe already advances its own reject/duplicate/outlier path.
		val measured = measurement as? PositionErrorMeasurementResult.Measured
		val learning = if (measured != null) learningLaw.observe(measured.sample)
			else learningLaw.advanceWithoutMeasurement(now, epoch)
		val state = learning.state
		check(state.lineage == null || state.lastStateAdvanceAtNanos == now)
		val application = if (baseHip.position == null && epoch != null) {
			PositionCorrectionApplication.prepare(prediction, state, baseHip, assignment, tick.expectedSpace, now)
		} else null
		// No callback, reset, rebuild or handoff between this projection and final commit.
		val projection = if (baseHip.position != null) sink.projectMainEffectiveHipTarget(baseHip, assignment, now) else null
		val selected = continuity.select(PositionCorrectionSolverContinuityInput(now, tick.resolvedAtNanos,
			tick.expectedSpace, assignment, baseHip, state, application as? PositionCorrectionApplicationResult.Ready,
			(projection as? MainEffectiveHipTargetResult.Available)?.target))
		val finalMap = immutableMap(resolved.mapValues { it.value.solverConstraint() } + (TrackerPosition.HIP to selected.constraint))
		// Last side-effect operation. Partial receipts are diagnostics, never a second-write trigger.
		val receipt = immutableMap(sink.applySolver(finalMap, assignment))
		return PositionCorrectionRuntimeTickResult.Processed(tick.tickSequence, now, assembly, prediction,
			measurement, learning, application, projection, selected, finalMap, receipt,
			PositionCorrectionRuntimeCallCounts(if (prediction != null) 1 else 0, if (measurement != null) 1 else 0,
				if (measured != null) 1 else 0, if (measured == null) 1 else 0, if (application != null) 1 else 0,
				if (projection != null) 1 else 0, 1, 1))
	}

	private fun preflight(t: PositionCorrectionRuntimeTick): PositionCorrectionRuntimeRejectionReason? = when {
		t.tickSequence < 0 -> PositionCorrectionRuntimeRejectionReason.TICK_SEQUENCE_INVALID
		t.tickSequence == lastSequence -> PositionCorrectionRuntimeRejectionReason.DUPLICATE_TICK
		lastSequence?.let { t.tickSequence < it } == true -> PositionCorrectionRuntimeRejectionReason.TICK_SEQUENCE_ROLLBACK
		t.nowNanos < 0 -> PositionCorrectionRuntimeRejectionReason.TIME_INVALID
		lastNow?.let { t.nowNanos < it } == true -> PositionCorrectionRuntimeRejectionReason.TIME_ROLLBACK
		t.resolvedAtNanos != t.nowNanos -> PositionCorrectionRuntimeRejectionReason.RESOLVED_TIME_MISMATCH
		t.assignment.generation < 0 -> PositionCorrectionRuntimeRejectionReason.ASSIGNMENT_GENERATION_INVALID
		else -> null
	}

	private fun assemble(t: PositionCorrectionRuntimeTick): PositionCorrectionPredictorAssemblyResult {
		fun unavailable(reason: PositionCorrectionAssemblyFailure) = PositionCorrectionPredictorAssemblyResult.Unavailable(reason)
		val s = t.predictorSources ?: return unavailable(PositionCorrectionAssemblyFailure.SOURCES_ABSENT)
		if (s.rawHmd.space != t.expectedSpace) return unavailable(PositionCorrectionAssemblyFailure.HMD_SPACE_MISMATCH)
		if (s.rawImu.space != t.expectedSpace) return unavailable(PositionCorrectionAssemblyFailure.IMU_SPACE_MISMATCH)
		if (s.rawHmd.provenance.commonWorldEpoch != s.rawImu.provenance.commonWorldEpoch)
			return unavailable(PositionCorrectionAssemblyFailure.COMMON_WORLD_EPOCH_MISMATCH)
		if (s.rawHmd.provenance.sampleAtNanos > t.nowNanos) return unavailable(PositionCorrectionAssemblyFailure.FUTURE_HMD_SAMPLE)
		if (s.rawImu.provenance.sampleAtNanos > t.nowNanos) return unavailable(PositionCorrectionAssemblyFailure.FUTURE_IMU_SAMPLE)
		if (s.fixedCalibration.hmdSourceId != s.rawHmd.source.sourceId) return unavailable(PositionCorrectionAssemblyFailure.FIXED_HMD_SOURCE_MISMATCH)
		if (s.fixedCalibration.bodyModelId != s.bodyModel.identity.modelId) return unavailable(PositionCorrectionAssemblyFailure.FIXED_BODY_MODEL_MISMATCH)
		return PositionCorrectionPredictorAssemblyResult.Available(MainDecoupledHipInput(s.rawHmd, s.rawImu,
			s.bodyModel, s.fixedCalibration, t.expectedSpace, t.assignment.generation, t.nowNanos, t.tickSequence))
	}
}

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(source))
