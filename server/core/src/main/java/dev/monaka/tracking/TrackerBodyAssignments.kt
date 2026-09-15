package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

data class LogicalTracker(val sourceId: String, val trackerId: String) {
	init { require(sourceId.isNotBlank() && trackerId.isNotBlank()) }
	// Length prefixes prevent separator/Unicode/concatenation collisions.
	val observationId: String get() = "mtp:${sourceId.length}:$sourceId${trackerId.length}:$trackerId"
	val isFeedback: Boolean get() = FeedbackExclusion.isOutput(sourceId) || FeedbackExclusion.isOutput(trackerId)
}

/** Persistent identity only. Sessions, hardware slots and body roles never enter C1. */
class TrackerBodyAssignments(initial: Map<LogicalTracker, TrackerPosition> = emptyMap()) {
	data class Snapshot(val generation: Long, val entries: Map<LogicalTracker, TrackerPosition>)
	@Volatile private var state = Snapshot(0, initial.toMap())
	init { require(initial.keys.none { it.isFeedback }) }
	fun snapshot(): Snapshot = state
	@Synchronized fun replace(entries: Map<LogicalTracker, TrackerPosition>) {
		require(entries.keys.none { it.isFeedback }) { "Output identities cannot be assigned as raw input" }
		if (state.entries != entries) state = Snapshot(state.generation + 1, entries.toMap())
	}
	@Synchronized fun assign(key: LogicalTracker, target: TrackerPosition) = replace(state.entries + (key to target))
	@Synchronized fun unassign(key: LogicalTracker) = replace(state.entries - key)

	/** Migration is all-or-nothing and requires an explicit Bridge identity for each serial. */
	@Synchronized fun migrateLegacy(
		serialTargets: Map<String, TrackerPosition>,
		bridgeMapping: Map<String, LogicalTracker>,
	) {
		require(bridgeMapping.keys.containsAll(serialTargets.keys)) { "Missing explicit Bridge mapping" }
		val migrated = serialTargets.map { (serial, target) -> bridgeMapping.getValue(serial) to target }
		require(migrated.map { it.first }.distinct().size == migrated.size) { "Ambiguous Bridge mapping" }
		for ((key, target) in migrated) require(state.entries[key]?.let { it == target } != false)
		replace(state.entries + migrated)
	}
}
