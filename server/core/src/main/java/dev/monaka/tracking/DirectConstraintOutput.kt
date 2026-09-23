package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus

/** Server-thread output view of the resolver result. No source selection, clock, hold or IK. */
class DirectConstraintOutput(
	assignment: TrackerBodyAssignments.Snapshot,
	nextId: () -> Int,
) : AutoCloseable {
	private val bodies = assignment.targets.filterValues { it.outputMode == OutputMode.DIRECT }.keys
	val trackers: Map<TrackerPosition, Tracker> = bodies.associateWith { body ->
		requireNotNull(body.trackerRole) { "Direct output requires a SteamVR role: $body" }
		Tracker(
			null, nextId(), PREFIX + body.name, trackerPosition = body,
			hasPosition = true, hasRotation = true, isInternal = true, isComputed = true,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
		).also {
			it.resolvedDirectConstraint = EffectiveConstraint(body)
			it.status = TrackerStatus.DISCONNECTED
		}
	}

	fun apply(constraints: Map<TrackerPosition, EffectiveConstraint>, paused: Boolean = false) {
		for ((body, tracker) in trackers) {
			val value = if (paused) EffectiveConstraint(body) else constraints[body] ?: EffectiveConstraint(body)
			require(value.target == body)
			// The serializer uses nullable components directly, never fixed capability flags or held fields.
			tracker.resolvedDirectConstraint = value
			value.position?.let { tracker.position = it.value }
			value.rotation?.let { tracker.setRotation(it.value) }
			tracker.status = when {
				value.position != null && value.rotation != null -> TrackerStatus.OK
				value.rotation != null -> TrackerStatus.OCCLUDED
				else -> TrackerStatus.DISCONNECTED
			}
		}
	}

	override fun close() = apply(emptyMap())
	companion object { const val PREFIX = "monaka-direct:resolved-v1:" }
}
