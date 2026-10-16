package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

data class LogicalTracker(val sourceId: String, val trackerId: String, val publisherId: String) {
	init { dev.monaka.tracking.revision.PersistentTrackerIdentity(publisherId, sourceId, trackerId) }
	// Length prefixes prevent separator/Unicode/concatenation collisions.
	val observationId: String get() = "mtp:${publisherId.length}:$publisherId${sourceId.length}:$sourceId${trackerId.length}:$trackerId"
	val lifetimeId: String get() = "${publisherId.length}:$publisherId$sourceId"
	val isFeedback: Boolean get() = FeedbackExclusion.isOutput(publisherId) || FeedbackExclusion.isOutput(sourceId) || FeedbackExclusion.isOutput(trackerId)
}

data class TrackerReference(val observationId: String, val mtp: LogicalTracker? = null) {
	init {
		require(observationId.isNotBlank() && !FeedbackExclusion.isOutput(observationId))
		require(mtp == null || (!mtp.isFeedback && mtp.observationId == observationId))
	}
	companion object {
		fun mtp(key: LogicalTracker) = TrackerReference(key.observationId, key)
		fun slime(persistentName: String): TrackerReference {
            require(persistentName.isNotBlank() && !FeedbackExclusion.isOutput(persistentName))
            return TrackerReference("slime:$persistentName")
        }
	}
}

enum class OutputMode { IK, DIRECT, HYBRID }

enum class ContinuityPolicy { NONE, BACKGROUND_IK }

data class MainTrackerAssignment(
	val mainTracker: TrackerReference,
	val rotationFallbackTracker: TrackerReference? = null,
	val outputMode: OutputMode = OutputMode.IK,
	val useAsIkConstraint: Boolean = true,
	val continuity: ContinuityPolicy = if (outputMode == OutputMode.HYBRID) ContinuityPolicy.BACKGROUND_IK else ContinuityPolicy.NONE,
	val sourceSelection: QSixDofSourceSelection? = null,
) {
	init {
		require(sourceSelection == null || (mainTracker == sourceSelection.mainTracker &&
			rotationFallbackTracker == sourceSelection.rotationFallbackTracker)) { "Source selection and runtime assignment disagree" }
		require(mainTracker != rotationFallbackTracker) { "Main cannot be its own external fallback" }
		require((outputMode == OutputMode.HYBRID) == (continuity == ContinuityPolicy.BACKGROUND_IK)) { "Background continuity requires hybrid output" }
		require(outputMode != OutputMode.HYBRID || useAsIkConstraint) { "Hybrid requires live background IK participation" }
	}
}

/** Persistent identity only. Sessions, hardware slots and body roles never enter C1. */
class TrackerBodyAssignments(initial: Map<LogicalTracker, TrackerPosition> = emptyMap()) {
	data class Snapshot(val generation: Long, val targets: Map<TrackerPosition, MainTrackerAssignment>) {
		val entries: Map<LogicalTracker, TrackerPosition> get() = targets.flatMap { (target, assignment) ->
			listOfNotNull(assignment.mainTracker.mtp, assignment.rotationFallbackTracker?.mtp).map { it to target }
		}.toMap()
	}
	@Volatile private var state = Snapshot(0, java.util.Collections.unmodifiableMap(LinkedHashMap(migrate(initial))))
	fun snapshot(): Snapshot = state
	@Synchronized fun replace(entries: Map<LogicalTracker, TrackerPosition>) {
		replaceTargets(migrate(entries))
	}
	@Synchronized fun replaceTargets(targets: Map<TrackerPosition, MainTrackerAssignment>) {
        val directRoles = targets.filterValues { it.outputMode != OutputMode.IK }.keys.map {
            requireNotNull(it.trackerRole) { "Direct output requires a SteamVR role: $it" }
        }
        require(directRoles.distinct().size == directRoles.size) { "Direct output roles must be unique" }
        require(directRoles.all { role -> targets.keys.count { it.trackerRole == role } == 1 }) {
            "Direct and IK assignments cannot share the same SteamVR output role"
        }
		val refs = targets.values.flatMap { listOfNotNull(it.mainTracker, it.rotationFallbackTracker) }
		require(refs.map { it.observationId }.distinct().size == refs.size) { "A tracker can belong to only one body target" }
		if (state.targets != targets) state = Snapshot(Math.incrementExact(state.generation), java.util.Collections.unmodifiableMap(LinkedHashMap(targets)))
	}
	@Synchronized fun configure(
		target: TrackerPosition, main: TrackerReference, fallback: TrackerReference? = null,
		outputMode: OutputMode = OutputMode.IK, useAsIkConstraint: Boolean = true,
		continuity: ContinuityPolicy = if (outputMode == OutputMode.HYBRID) ContinuityPolicy.BACKGROUND_IK else ContinuityPolicy.NONE,
	) = replaceTargets(state.targets + (target to MainTrackerAssignment(main, fallback, outputMode, useAsIkConstraint, continuity)))
	@Synchronized fun assign(key: LogicalTracker, target: TrackerPosition) {
        val previous = state.targets.values.singleOrNull { it.mainTracker.mtp == key }
        val next = without(key)
        val current = next[target]
        require(current == null || current.mainTracker.mtp == key) { "Target already has a Main; use configure for explicit replacement" }
        replaceTargets(next + (target to (previous ?: MainTrackerAssignment(TrackerReference.mtp(key)))))
    }
	/** UI/configuration action only; metadata arrival never calls this method. */
	@Synchronized fun selectQSource(target: TrackerPosition, q: TrackerReference, capabilities: DeclaredTrackerCapabilities?) {
		val slime = q.mtp == null && q.observationId.startsWith("slime:")
		require(slime || capabilities?.supportsQ == true) { "Q Source requires declared orientation support" }
		val current = state.targets[target]
		val previous = current?.sourceSelection ?: current?.let {
			// Existing explicit Main/Fallback assignments are user choices, never new auto defaults.
			QSixDofSourceSelection(it.rotationFallbackTracker ?: it.mainTracker,
				it.mainTracker.takeIf { ref -> ref.mtp != null }, true)
		}
		val selection = previous?.copy(qSource = q) ?: QSixDofSourceSelection(q,
			q.takeIf { !slime && capabilities?.supportsSixDof == true })
		applySourceSelection(target, selection)
	}
	@Synchronized fun selectSixDofSource(target: TrackerPosition, source: TrackerReference?, capabilities: DeclaredTrackerCapabilities? = null) {
		require(source == null || capabilities?.supportsSixDof == true) { "6DoF Source requires position and orientation support" }
		val current = state.targets[target]
		val previous = current?.sourceSelection ?: current?.let { QSixDofSourceSelection(
			it.rotationFallbackTracker ?: it.mainTracker, it.mainTracker.takeIf { ref -> ref.mtp != null }, true) }
		if (previous == null && source == null) return
		configureSources(target, previous?.qSource, source, true)
	}
	/** Explicit GUI transaction only. Arrival/reconnect never calls this method. */
	@Synchronized fun configureSources(target: TrackerPosition, q: TrackerReference?, sixDof: TrackerReference?, manual: Boolean) {
		if (q == null && sixDof == null) { replaceTargets(state.targets - target); return }
		applySourceSelection(target, QSixDofSourceSelection(q, sixDof, manual))
	}
	private fun applySourceSelection(target: TrackerPosition, selection: QSixDofSourceSelection) {
		val current = state.targets[target] ?: MainTrackerAssignment(selection.mainTracker)
		replaceTargets(state.targets + (target to current.copy(mainTracker = selection.mainTracker,
			rotationFallbackTracker = selection.rotationFallbackTracker, sourceSelection = selection)))
	}
    private fun without(key: LogicalTracker) = state.targets.mapNotNull { (target, value) ->
        when (key) {
            value.mainTracker.mtp -> null
            value.rotationFallbackTracker?.mtp -> target to value.copy(rotationFallbackTracker = null, sourceSelection = null)
            else -> target to value
        }
    }.toMap()
    @Synchronized fun unassign(key: LogicalTracker) = replaceTargets(without(key))

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
	companion object {
		private fun migrate(entries: Map<LogicalTracker, TrackerPosition>): Map<TrackerPosition, MainTrackerAssignment> {
			require(entries.keys.none { it.isFeedback })
			require(entries.values.distinct().size == entries.size) { "Ambiguous legacy assignment: select one Main and explicit fallback" }
			val migrated = dev.monaka.tracking.revision.LegacyAssignmentMigration.migrate(entries.map { it.key to it.value })
            require(migrated is dev.monaka.tracking.revision.LegacyAssignmentMigrationResult.Success)
            return migrated.assignments.entries.mapValues { (_, relation) -> MainTrackerAssignment(TrackerReference.mtp(relation.main)) }
		}
	}
}
