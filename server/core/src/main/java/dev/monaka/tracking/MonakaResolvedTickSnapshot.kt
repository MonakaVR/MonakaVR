package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition
import java.util.Collections

/** One production tick's assignment context and resolved values, without writeback ownership. */
class MonakaResolvedTickSnapshot(
	val tickSequence: Long,
	val nowNanos: Long,
	val resolvedAtNanos: Long,
	val paused: Boolean,
	val assignment: TrackerBodyAssignments.Snapshot,
	constraints: Map<TrackerPosition, EffectiveConstraint>,
	/** Opaque physical Tracker receipt clock domain, captured before runtime now. */
	val trackerReceiptCutoffSystemNanos: Long,
) {
	// Components and their Vector3/Quaternion values are immutable; only the map needs copying.
	val constraints: Map<TrackerPosition, EffectiveConstraint> = Collections.unmodifiableMap(LinkedHashMap(constraints))

	init {
		require(tickSequence >= 0)
		require(nowNanos >= 0)
		require(resolvedAtNanos == nowNanos)
		require(assignment.generation >= 0)
	}
}
