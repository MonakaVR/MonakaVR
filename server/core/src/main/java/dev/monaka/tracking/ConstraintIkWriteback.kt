package dev.monaka.tracking

import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.processor.skeleton.SkeletonInputView
import dev.slimevr.tracking.trackers.*
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Private capability-correct inputs to the existing solver; never register these as raw trackers. */
class ConstraintIkWriteback(private val skeleton: HumanSkeleton) : AutoCloseable {
	data class ComponentMask(val position: Boolean, val rotation: Boolean)
	private var managed = emptySet<TrackerPosition>()
	private val proxies = linkedMapOf<TrackerPosition, Tracker>()
	private var stableNames = emptyMap<TrackerPosition, String>()
	private var generation = -1L
	var topologyRebuilds = 0
		private set
	private var closed = false

	init { skeleton.setConstraintInputView(::inputView) }
	fun masks(): Map<TrackerPosition, ComponentMask> = proxies.mapValues { ComponentMask(it.value.hasPosition, it.value.hasRotation) }

	private fun inputView(raw: List<Tracker>): SkeletonInputView {
		val untouched = raw.filter { FeedbackExclusion.accepts(it) && it.trackerPosition !in managed }
		val constraints = untouched + proxies.values
		// A position-only body constraint must not inject an identity rotation into FK.
		// Head is also the positional root anchor and handles missing orientation explicitly.
		val rotations = untouched + proxies.values.filter { it.hasRotation || it.trackerPosition == TrackerPosition.HEAD }
		return SkeletonInputView(rotations, constraints, untouched.map { it.name }.toSet() + stableNames.values)
	}

	fun apply(
		constraints: Map<TrackerPosition, EffectiveConstraint>,
		assignment: TrackerBodyAssignments.Snapshot,
		@Suppress("UNUSED_PARAMETER") historyRevision: Long = 0,
	) {
		applySolver(constraints.mapValues { it.value.solverConstraint() }, assignment)
	}

	internal sealed interface ApplyResult {
		data object Applied : ApplyResult
		data object Paused : ApplyResult
		data class Rejected(val reason: SolverTargetFailure) : ApplyResult
	}
	private fun stableName(target: TrackerPosition, relation: MainTrackerAssignment) =
		"monaka-private:${target.name}:${relation.mainTracker.observationId}"

	/** Current raw Main -> effective HIP_CENTER. No cached calibration or teacher calibration. */
	internal fun projectMainEffectiveHipTarget(raw: EffectiveConstraint, assignment: TrackerBodyAssignments.Snapshot,
		nowNanos: Long): MainEffectiveHipTargetResult {
		check(!closed)
		val relation = assignment.targets[TrackerPosition.HIP]
		val calibration = relation?.let { skeleton.ikSolver.calibrationFor(stableName(TrackerPosition.HIP, it), TrackerPosition.HIP.bodyPart) }
		return MainEffectiveHipTarget.project(raw, assignment, nowNanos, calibration)
	}

	/** Diagnostic/test only; actual active IKConstraint.getPosition(), not proxy storage. */
	internal fun effectivePositionTargetSnapshot(target: TrackerPosition): Vector3? =
		proxies[target]?.let { skeleton.ikSolver.effectivePositionTargetFor(it.name, target.bodyPart) }

	/** Typed sink remains dormant for correction. Generic production apply adapts raw origins. */
	internal fun applySolver(
		constraints: Map<TrackerPosition, SolverEffectiveConstraint>,
		assignment: TrackerBodyAssignments.Snapshot,
	): Map<TrackerPosition, ApplyResult> {
		check(!closed)
		if (skeleton.getPauseTracking()) return constraints.mapValues { ApplyResult.Paused }
		val results = linkedMapOf<TrackerPosition, ApplyResult>()
		val ikAssignments = assignment.targets.filterValues { it.useAsIkConstraint }
		val targets = ikAssignments.keys
		var rebuild = targets != managed || generation != assignment.generation
		// Sample/history invalidation is handled by the cache. Calibration belongs to
		// the stable Main/body relation, not the current modality or fallback sample.
		stableNames = ikAssignments.mapValues { (target, relation) ->
			stableName(target, relation)
		}
		managed = targets
		generation = assignment.generation
		for (target in proxies.keys.toList()) {
			if (target !in targets) { proxies.remove(target); rebuild = true }
		}
		for (target in targets) {
			val constraint = constraints[target]
			var position = constraint?.position?.value
			val rotation = constraint?.rotation?.value
			val reference = constraint?.positionReference ?: SolverPositionReference.NONE
			var failure: SolverTargetFailure? = null
			if (constraint != null && (constraint.target != target || (position == null) != (reference == SolverPositionReference.NONE)))
				failure = SolverTargetFailure.TARGET_REFERENCE_INVALID
			else if ((position != null && !SolverCalibrationTransform.finite(position)) ||
				(rotation != null && !SolverCalibrationTransform.valid(rotation))) failure = SolverTargetFailure.INPUT_INVALID
			else if (position != null && reference == SolverPositionReference.IK_EFFECTIVE_TARGET) {
				// Same exact rotation subsequently stored on an unfiltered, no-reset proxy.
				val calibration = skeleton.ikSolver.calibrationFor(stableNames.getValue(target), target.bodyPart)
				val offset = SolverCalibrationTransform.rotatedOffset(rotation ?: Quaternion.IDENTITY, calibration)
				if (offset == null) failure = SolverTargetFailure.CALIBRATION_INVALID
				else {
					position -= offset
					if (!SolverCalibrationTransform.finite(position)) failure = SolverTargetFailure.PRECOMPENSATION_NONFINITE
				}
			}
			if (failure != null) {
				// Remove stale input while retaining the stable calibration name across rebuild.
				if (proxies.remove(target) != null) rebuild = true
				results[target] = ApplyResult.Rejected(failure)
				continue
			}
			results[target] = ApplyResult.Applied
			if (position == null && rotation == null) {
				if (proxies.remove(target) != null) rebuild = true
				continue
			}
			var proxy = proxies[target]
			if (proxy == null || proxy.name != stableNames[target] || proxy.hasPosition != (position != null) || proxy.hasRotation != (rotation != null)) {
				proxy = Tracker(
					null, -10000 - target.ordinal, stableNames.getValue(target),
					trackerPosition = target, hasPosition = position != null, hasRotation = rotation != null,
					allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
				)
				proxy.status = TrackerStatus.OK
				proxies[target] = proxy
				rebuild = true
			}
			if (rotation != null) proxy.setRotation(rotation)
			if (position != null) proxy.position = position
		}
		if (rebuild) {
			skeleton.refreshConstraintInputs()
			topologyRebuilds++
		}
		return results
	}
	override fun close() {
		if (closed) return
		proxies.clear(); managed = emptySet(); generation = -1
		skeleton.setConstraintInputView(null); closed = true
	}
}
