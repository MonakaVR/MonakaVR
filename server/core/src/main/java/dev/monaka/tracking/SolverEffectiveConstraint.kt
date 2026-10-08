package dev.monaka.tracking

import dev.slimevr.tracking.processor.skeleton.IKConstraint
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Position semantics at the private IK boundary, independent of provenance strings. */
internal enum class SolverPositionReference { NONE, TRACKER_ORIGIN, IK_EFFECTIVE_TARGET }

internal data class SolverEffectiveConstraint(
	val target: TrackerPosition,
	val position: ResolvedComponent<Vector3>? = null,
	val rotation: ResolvedComponent<Quaternion>? = null,
	val positionReference: SolverPositionReference = SolverPositionReference.NONE,
)

internal fun EffectiveConstraint.solverConstraint() = SolverEffectiveConstraint(
	target, position, rotation,
	if (position == null) SolverPositionReference.NONE else SolverPositionReference.TRACKER_ORIGIN,
)

internal enum class SolverTargetFailure {
	TARGET_REFERENCE_INVALID, INPUT_INVALID, CALIBRATION_INVALID, PRECOMPENSATION_NONFINITE,
	MAIN_OWNERSHIP_INVALID, MAIN_ROTATION_UNAVAILABLE, PROJECTION_NONFINITE,
}

/** No normalization: matches IKConstraint.getPosition and the unfiltered/no-reset proxy. */
internal object SolverCalibrationTransform {
	fun finite(v: Vector3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
	fun valid(q: Quaternion): Boolean = listOf(q.w, q.x, q.y, q.z).all(Float::isFinite) &&
		q.lenSq().let { it.isFinite() && it > 1e-10f }
	// An inverse of a valid large nonunit tracker quaternion may have a small norm.
	// Calibration/product validity must not reuse the raw-input admission threshold.
	private fun validTransform(q: Quaternion): Boolean = listOf(q.w, q.x, q.y, q.z).all(Float::isFinite) &&
		q.lenSq().let { it.isFinite() && it > 0f }
	fun rotatedOffset(rotation: Quaternion, calibration: IKConstraint.Calibration?): Vector3? {
		if (!valid(rotation)) return null
		val offset = calibration?.offset ?: Vector3.NULL
		val rotationOffset = calibration?.rotationOffset ?: Quaternion.IDENTITY
		if (!finite(offset) || !validTransform(rotationOffset)) return null
		val combined = rotation * rotationOffset
		if (!validTransform(combined)) return null
		return combined.sandwich(offset).takeIf(::finite)
	}
}

internal sealed interface MainEffectiveHipTargetResult {
	data class Available(val target: MainEffectiveHipTarget) : MainEffectiveHipTargetResult
	data class Unavailable(val reason: SolverTargetFailure) : MainEffectiveHipTargetResult
}

/** Derived HIP_CENTER; factory binds it to the exact raw bundle and assignment, never an arbitrary vector. */
internal class MainEffectiveHipTarget private constructor(
	val position: Vector3,
	private val raw: EffectiveConstraint,
	val assignmentGeneration: Long,
	private val relation: MainTrackerAssignment,
	val projectedAtNanos: Long,
) {
	val target = TrackerPosition.HIP
	val bodyReference = PositionBodyReference.HIP_CENTER
	val sourceId get() = raw.position!!.sourceId
	val quality get() = raw.position!!.quality
	val observedAtNanos get() = raw.position!!.observedAtNanos
	fun matches(base: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot, nowNanos: Long) =
		projectedAtNanos == nowNanos && raw == base && assignmentGeneration == assignment.generation && assignment.targets[target] == relation

	companion object {
		/** Boundary factory. Call with current solver calibration in the same server-thread phase as writeback. */
		internal fun project(raw: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot,
			now: Long, calibration: IKConstraint.Calibration?): MainEffectiveHipTargetResult {
			fun reject(reason: SolverTargetFailure) = MainEffectiveHipTargetResult.Unavailable(reason)
			val relation = assignment.targets[TrackerPosition.HIP]
			val p = raw.position
			if (now < 0 || assignment.generation < 0 || raw.target != TrackerPosition.HIP || relation == null ||
				!relation.useAsIkConstraint || p == null || p.sourceId != relation.mainTracker.observationId ||
				FeedbackExclusion.isOutput(p.sourceId) || !p.quality.usable || p.observedAtNanos !in 0..now)
				return reject(SolverTargetFailure.MAIN_OWNERSHIP_INVALID)
			val r = raw.rotation ?: return reject(SolverTargetFailure.MAIN_ROTATION_UNAVAILABLE)
			if (r.sourceId != p.sourceId || !r.quality.usable || r.observedAtNanos !in 0..now ||
				!SolverCalibrationTransform.valid(r.value) || !SolverCalibrationTransform.finite(p.value))
				return reject(SolverTargetFailure.INPUT_INVALID)
			val offset = SolverCalibrationTransform.rotatedOffset(r.value, calibration)
				?: return reject(SolverTargetFailure.CALIBRATION_INVALID)
			val position = p.value + offset
			if (!SolverCalibrationTransform.finite(position)) return reject(SolverTargetFailure.PROJECTION_NONFINITE)
			return MainEffectiveHipTargetResult.Available(MainEffectiveHipTarget(position, raw, assignment.generation, relation, now))
		}
	}
}
