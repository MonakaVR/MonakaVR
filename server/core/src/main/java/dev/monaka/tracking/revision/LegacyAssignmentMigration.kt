package dev.monaka.tracking.revision

sealed interface LegacyAssignmentMigrationResult<Target : Any, Tracker : Any> {
	data class Success<Target : Any, Tracker : Any>(
		val assignments: MainFallbackAssignmentSet<Target, Tracker>,
	) : LegacyAssignmentMigrationResult<Target, Tracker>

	data class Ambiguous<Target : Any, Tracker : Any>(
		val targets: Set<Target>,
	) : LegacyAssignmentMigrationResult<Target, Tracker>
}

/**
 * Contract-independent legacy migration helper.
 *
 * Legacy input is tracker -> target. A target with more than one distinct tracker
 * is ambiguous under the new one-explicit-Main model and therefore fails closed.
 * Exact duplicate pairs are tolerated. No rotation fallback is inferred.
 */
object LegacyAssignmentMigration {
	fun <Target : Any, Tracker : Any> migrate(
		legacy: Iterable<Pair<Tracker, Target>>,
	): LegacyAssignmentMigrationResult<Target, Tracker> {
		val byTarget = linkedMapOf<Target, LinkedHashSet<Tracker>>()
		for ((tracker, target) in legacy) {
			byTarget.getOrPut(target) { linkedSetOf() }.add(tracker)
		}

		val ambiguousTargets = byTarget
			.filterValues { trackers -> trackers.size > 1 }
			.keys
			.toCollection(linkedSetOf())
		if (ambiguousTargets.isNotEmpty()) {
			return LegacyAssignmentMigrationResult.Ambiguous(ambiguousTargets)
		}

		val migrated = byTarget.mapValues { (_, trackers) ->
			MainFallbackAssignment(main = trackers.single())
		}
		return LegacyAssignmentMigrationResult.Success(
			MainFallbackAssignmentSet.from(migrated),
		)
	}
}
