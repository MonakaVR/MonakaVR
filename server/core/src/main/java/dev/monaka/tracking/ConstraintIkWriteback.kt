package dev.monaka.tracking

import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.processor.skeleton.SkeletonInputView
import dev.slimevr.tracking.trackers.*

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
		check(!closed)
		if (skeleton.getPauseTracking()) return
		val targets = assignment.targets.keys
		var rebuild = targets != managed || generation != assignment.generation
		// Sample/history invalidation is handled by the cache. Calibration belongs to
		// the stable Main/body relation, not the current modality or fallback sample.
		stableNames = assignment.targets.mapValues { (target, relation) ->
			"monaka-private:${target.name}:${relation.mainTracker.observationId}"
		}
		managed = targets
		generation = assignment.generation
		for (target in proxies.keys.toList()) {
			if (target !in targets) { proxies.remove(target); rebuild = true }
		}
		for (target in targets) {
			val constraint = constraints[target]
			val position = constraint?.position?.value
			val rotation = constraint?.rotation?.value
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
			if (position != null) proxy.position = position
			if (rotation != null) proxy.setRotation(rotation)
		}
		if (rebuild) {
			skeleton.refreshConstraintInputs()
			topologyRebuilds++
		}
	}
	override fun close() {
		if (closed) return
		proxies.clear(); managed = emptySet(); generation = -1
		skeleton.setConstraintInputView(null); closed = true
	}
}
