package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Explicit caller policy; no production default or physical tuning. */
internal data class PositionCorrectionReacquisitionTuning(val reacquireDurationNanos: Long) {
	init { require(reacquireDurationNanos > 0) }
}

internal enum class PositionCorrectionSolverContinuityPhase {
	UNINITIALIZED, FALLBACK_ACTIVE, REACQUIRING, MAIN_DIRECT, UNAVAILABLE,
}
internal enum class PositionCorrectionSolverContinuityReason {
	COLD_MAIN_DIRECT, FALLBACK_READY, FALLBACK_UNAVAILABLE, MAIN_REACQUIRE_STARTED,
	MAIN_REACQUIRE_PROGRESS, MAIN_REACQUIRE_COMPLETE, MAIN_DIRECT, MAIN_RELOSS,
	CONTEXT_CHANGED, NO_SAFE_FALLBACK_ANCHOR, MALFORMED_TICK, TIME_ROLLBACK, NUMERIC_INVALID,
}

internal data class PositionCorrectionSolverContinuityInput(
	val nowNanos: Long,
	val resolvedAtNanos: Long,
	val expectedSpace: CoordinateSpace,
	val assignment: TrackerBodyAssignments.Snapshot,
	val baseHipConstraint: EffectiveConstraint,
	val correctionState: PositionCorrectionStateSnapshot,
	val fallbackApplication: PositionCorrectionApplicationResult.Ready?,
	val mainEffectiveHipTarget: MainEffectiveHipTarget? = null,
)

/** Last selected solver-facing fallback position, never merely a prepared candidate. */
internal data class PositionCorrectionFallbackAnchor(
	val position: Vector3,
	val positionObservedAtNanos: Long,
	val coordinateSpace: CoordinateSpace,
	val assignmentGeneration: Long,
	val lineage: PositionCorrectionLearningLineage,
)

/** Diagnostic only; cannot authorize application or learning. */
internal data class PositionCorrectionSolverContinuitySnapshot(
	val phase: PositionCorrectionSolverContinuityPhase,
	val coordinateSpace: CoordinateSpace?,
	val assignmentGeneration: Long?,
	val lastFallbackAnchor: PositionCorrectionFallbackAnchor?,
	val reacquireStartedAtNanos: Long?,
	val lastTickAtNanos: Long?,
)

/** Solver input only. No raw/prediction/visible-output conversion. */
internal data class PositionCorrectionSolverContinuityResult(
	val phase: PositionCorrectionSolverContinuityPhase,
	val constraint: SolverEffectiveConstraint,
	val reason: PositionCorrectionSolverContinuityReason,
)

/** Dormant pre-IK position selection. Caller must apply each selected constraint to its
 * solver sink in order. Selection is the commit point for the fallback anchor; abandoned
 * candidates never enter here. All inputs and time are supplied by the caller.
 */
internal class PositionCorrectionSolverContinuity(private val tuning: PositionCorrectionReacquisitionTuning) {
	private data class Context(
		val space: CoordinateSpace,
		val generation: Long,
		val lineage: PositionCorrectionLearningLineage?,
		val relation: MainTrackerAssignment?,
	)
	private var phase = PositionCorrectionSolverContinuityPhase.UNINITIALIZED
	private var context: Context? = null
	private var anchor: PositionCorrectionFallbackAnchor? = null
	private var startedAt: Long? = null
	private var lastTick: Long? = null
	private var lastInput: PositionCorrectionSolverContinuityInput? = null
	private var lastResult: PositionCorrectionSolverContinuityResult? = null

	fun snapshot() = PositionCorrectionSolverContinuitySnapshot(phase, context?.space, context?.generation,
		anchor?.let { it.copy(position = copy(it.position)) }, startedAt, lastTick)

	fun select(input: PositionCorrectionSolverContinuityInput): PositionCorrectionSolverContinuityResult {
		val now = input.nowNanos
		if (lastTick?.let { now < it } == true)
			return PositionCorrectionSolverContinuityResult(PositionCorrectionSolverContinuityPhase.UNAVAILABLE,
				SolverEffectiveConstraint(TrackerPosition.HIP), PositionCorrectionSolverContinuityReason.TIME_ROLLBACK)
		if (input == lastInput) return lastResult!!.let {
			if (it.phase == PositionCorrectionSolverContinuityPhase.MAIN_DIRECT) it.copy(constraint = input.baseHipConstraint.solverConstraint()) else it
		}
		fun reject(reason: PositionCorrectionSolverContinuityReason): PositionCorrectionSolverContinuityResult {
			clearAnchor()
			// A rejected future tick cannot subsequently be used to rewind the controller.
			if (now >= 0) lastTick = now
			return finish(input, PositionCorrectionSolverContinuityPhase.UNAVAILABLE, SolverEffectiveConstraint(TrackerPosition.HIP), reason)
		}
		val base = input.baseHipConstraint
		val state = input.correctionState
		if (now < 0 || input.resolvedAtNanos != now || base.target != TrackerPosition.HIP || input.assignment.generation < 0 ||
			(state.lineage != null && state.lastStateAdvanceAtNanos != now) ||
			(base.position != null && input.fallbackApplication != null))
			return reject(PositionCorrectionSolverContinuityReason.MALFORMED_TICK)
		val relation = input.assignment.targets[TrackerPosition.HIP]
		val nextContext = Context(input.expectedSpace, input.assignment.generation, state.lineage, relation)
		val changed = context != null && context != nextContext
		val safeState = state.phase != PositionCorrectionPhase.UNINITIALIZED && state.lineage?.let {
			state.coordinateSpace == input.expectedSpace &&
				it.teacherEpoch.coordinateSpace == input.expectedSpace && it.predictionEpoch.coordinateSpace == input.expectedSpace &&
				it.teacherEpoch.assignmentGeneration == input.assignment.generation &&
				it.predictionEpoch.assignmentGeneration == input.assignment.generation &&
				it.teacherEpoch.bodyReference == PositionBodyReference.HIP_CENTER &&
				it.teacherEpoch.sourceId == relation?.mainTracker?.observationId
		} == true
		if (changed || !safeState) clearAnchor()
		context = nextContext
		lastTick = now
		if (relation == null || !relation.useAsIkConstraint) return reject(PositionCorrectionSolverContinuityReason.MALFORMED_TICK)

		if (base.position != null) {
			val p = base.position
			val r = base.rotation
			val main = relation.mainTracker.observationId
			if (FeedbackExclusion.isOutput(p.sourceId) || p.sourceId != main || !p.quality.usable ||
				p.observedAtNanos !in 0..now || r == null || !validRotation(r, main, now))
				return reject(PositionCorrectionSolverContinuityReason.MALFORMED_TICK)
			if (!finite(p.value)) return reject(PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
			val fallback = anchor
			if (fallback == null) {
				val reason = when {
					changed -> PositionCorrectionSolverContinuityReason.CONTEXT_CHANGED
					phase == PositionCorrectionSolverContinuityPhase.UNINITIALIZED -> PositionCorrectionSolverContinuityReason.COLD_MAIN_DIRECT
					phase == PositionCorrectionSolverContinuityPhase.MAIN_DIRECT -> PositionCorrectionSolverContinuityReason.MAIN_DIRECT
					else -> PositionCorrectionSolverContinuityReason.NO_SAFE_FALLBACK_ANCHOR
				}
				return finish(input, PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, base.solverConstraint(), reason)
			}
			val mainTarget = input.mainEffectiveHipTarget
			if (mainTarget == null || !mainTarget.matches(base, input.assignment, now))
				return reject(PositionCorrectionSolverContinuityReason.MALFORMED_TICK)
			val first = startedAt == null
			if (first) startedAt = now
			// Ordered, nonnegative subtraction; no overflow-prone end-time addition.
			val elapsed = now - startedAt!!
			if (elapsed >= tuning.reacquireDurationNanos) {
				clearAnchor()
				return finish(input, PositionCorrectionSolverContinuityPhase.MAIN_DIRECT, base.solverConstraint(),
					PositionCorrectionSolverContinuityReason.MAIN_REACQUIRE_COMPLETE)
			}
			val u = elapsed.toDouble() / tuning.reacquireDurationNanos.toDouble()
			val a = fallback.position
			fun blend(x: Float, y: Float) = (x.toDouble() * (1.0 - u) + y.toDouble() * u).toFloat()
			val position = if (first) copy(a) else Vector3(blend(a.x, mainTarget.position.x), blend(a.y, mainTarget.position.y), blend(a.z, mainTarget.position.z))
			if (!finite(position)) return reject(PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
			return finish(input, PositionCorrectionSolverContinuityPhase.REACQUIRING,
				SolverEffectiveConstraint(TrackerPosition.HIP, ResolvedComponent(position,
					"monaka-private:position-correction-reacquire-v1:HIP", ObservationQuality.DEGRADED,
					minOf(fallback.positionObservedAtNanos, mainTarget.observedAtNanos)), r, SolverPositionReference.IK_EFFECTIVE_TARGET),
				if (first) PositionCorrectionSolverContinuityReason.MAIN_REACQUIRE_STARTED
				else PositionCorrectionSolverContinuityReason.MAIN_REACQUIRE_PROGRESS)
		}

		val reloss = phase == PositionCorrectionSolverContinuityPhase.REACQUIRING || phase == PositionCorrectionSolverContinuityPhase.MAIN_DIRECT
		val candidate = input.fallbackApplication?.candidate
		if (candidate == null) {
			clearAnchor()
			return finish(input, PositionCorrectionSolverContinuityPhase.UNAVAILABLE, base.solverConstraint(),
				if (changed) PositionCorrectionSolverContinuityReason.CONTEXT_CHANGED
				else if (reloss) PositionCorrectionSolverContinuityReason.MAIN_RELOSS
				else PositionCorrectionSolverContinuityReason.FALLBACK_UNAVAILABLE)
		}
		val provenance = candidate.predictionProvenance
		val fallbackSource = relation.rotationFallbackTracker?.observationId
		if (!safeState || fallbackSource == null || candidate.applicationAtNanos != now ||
			candidate.target != TrackerPosition.HIP || candidate.bodyReference != PositionBodyReference.HIP_CENTER ||
			candidate.coordinateSpace != input.expectedSpace || candidate.correctionLineage != state.lineage ||
			candidate.predictionEpoch != state.lineage.predictionEpoch || candidate.correctionPhase != state.phase ||
			candidate.correctionWorld != state.correctionWorld ||
			candidate.positionSourceId != "monaka-private:position-correction-v1:HIP" ||
			candidate.positionObservedAtNanos !in 0..now || candidate.positionObservedAtNanos != provenance.inputEarliestAtNanos ||
			provenance.generatedAtNanos != now || provenance.epoch != candidate.predictionEpoch ||
			provenance.predictionSequence != candidate.predictionSequence ||
			provenance.inputLatestAtNanos !in provenance.inputEarliestAtNanos..now ||
			candidate.predictionEpoch.imuSourceId != fallbackSource ||
			FeedbackExclusion.isOutput(candidate.predictionEpoch.hmdSourceId) ||
			candidate.baseRotation != base.rotation || !validRotation(candidate.baseRotation, fallbackSource, now))
			return reject(PositionCorrectionSolverContinuityReason.MALFORMED_TICK)
		if (!finite(candidate.correctedPosition) || !finite(candidate.basePredictionPosition) || !finite(candidate.correctionWorld) ||
			(state.phase == PositionCorrectionPhase.EXPIRED && candidate.correctionWorld != Vector3(0f, 0f, 0f)))
			return reject(PositionCorrectionSolverContinuityReason.NUMERIC_INVALID)
		val selected = candidate.solverConstraint()
		val position = selected.position!!
		anchor = PositionCorrectionFallbackAnchor(copy(position.value), position.observedAtNanos, input.expectedSpace,
			input.assignment.generation, candidate.correctionLineage)
		startedAt = null
		return finish(input, PositionCorrectionSolverContinuityPhase.FALLBACK_ACTIVE, selected,
			if (changed) PositionCorrectionSolverContinuityReason.CONTEXT_CHANGED
			else if (reloss) PositionCorrectionSolverContinuityReason.MAIN_RELOSS
			else PositionCorrectionSolverContinuityReason.FALLBACK_READY)
	}

	private fun clearAnchor() { anchor = null; startedAt = null }
	private fun finish(input: PositionCorrectionSolverContinuityInput, next: PositionCorrectionSolverContinuityPhase,
		constraint: SolverEffectiveConstraint, reason: PositionCorrectionSolverContinuityReason): PositionCorrectionSolverContinuityResult {
		phase = next
		return PositionCorrectionSolverContinuityResult(next, constraint, reason).also { lastInput = input; lastResult = it }
	}
	private fun validRotation(r: ResolvedComponent<Quaternion>, owner: String, now: Long): Boolean {
		val q = r.value
		val norm = q.lenSq()
		return !FeedbackExclusion.isOutput(r.sourceId) && r.sourceId == owner && r.quality.usable &&
			r.observedAtNanos in 0..now && listOf(q.w, q.x, q.y, q.z).all(Float::isFinite) && norm.isFinite() && norm > 1e-10f
	}
	private fun finite(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
	private fun copy(v: Vector3) = Vector3(v.x, v.y, v.z)
}
