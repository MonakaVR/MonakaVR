package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus

/** Server-thread output view of the resolver result. No source selection, clock, hold or IK. */
class DirectConstraintOutput(
	assignment: TrackerBodyAssignments.Snapshot,
	private val space: dev.monaka.protocol.v2.CoordinateSpace,
	nextId: () -> Int,
) : AutoCloseable {
	private val bodies = assignment.targets.filterValues { it.outputMode != OutputMode.IK }.keys
	val trackers: Map<TrackerPosition, Tracker> = bodies.associateWith { body ->
		requireNotNull(body.trackerRole) { "Direct output requires a SteamVR role: $body" }
		Tracker(
			null, nextId(), PREFIX + body.name, trackerPosition = body,
			hasPosition = true, hasRotation = true, isInternal = true, isComputed = true,
			allowFiltering = false, allowReset = false, allowMounting = false, trackRotDirection = false,
		).also {
			it.monakaOutputPose = OutputPose(body, space)
			it.status = TrackerStatus.DISCONNECTED
		}
	}

	fun apply(constraints: Map<TrackerPosition, EffectiveConstraint>, paused: Boolean = false) {
		applyPoses(constraints.mapValues { (_, value) -> OutputPose(value.target, space, value.position, value.rotation) }, paused)
	}

	fun applyPoses(poses: Map<TrackerPosition, OutputPose>, paused: Boolean = false) {
		for ((body, tracker) in trackers) {
			val value = if (paused) OutputPose(body, space) else poses[body] ?: OutputPose(body, space)
			require(value.target == body && value.space == space)
			// The serializer uses nullable components directly, never fixed capability flags or held fields.
			tracker.monakaOutputPose = value
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
