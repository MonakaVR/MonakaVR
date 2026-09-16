package dev.monaka.tracking.revision

/**
 * Immutable, contract-independent assignment candidate for the coordinated
 * Main/Fallback revision.
 *
 * It deliberately has no body-role, protocol, runtime, freshness or IK
 * dependencies. Existing legacy single-source assignments migrate to Main-only
 * entries; no fallback is guessed.
 */
data class MainFallbackAssignmentSet<Target : Any, Tracker : Any> private constructor(
	val entries: Map<Target, MainFallbackAssignment<Tracker>>,
) {
	fun assign(
		target: Target,
		main: Tracker,
		rotationFallback: Tracker? = null,
	): MainFallbackAssignmentSet<Target, Tracker> =
		from(entries + (target to MainFallbackAssignment(main, rotationFallback)))

	fun unassign(target: Target): MainFallbackAssignmentSet<Target, Tracker> =
		from(entries - target)

	companion object {
		fun <Target : Any, Tracker : Any> empty(): MainFallbackAssignmentSet<Target, Tracker> =
			MainFallbackAssignmentSet(emptyMap())

		fun <Target : Any, Tracker : Any> from(
			entries: Map<Target, MainFallbackAssignment<Tracker>>,
		): MainFallbackAssignmentSet<Target, Tracker> =
			MainFallbackAssignmentSet(entries.toMap())

		/**
		 * Architecture Revision migration rule: a legacy one-tracker-per-target
		 * mapping becomes Main-only. No external fallback relation is inferred.
		 */
		fun <Target : Any, Tracker : Any> fromLegacyMainOnly(
			legacy: Map<Target, Tracker>,
		): MainFallbackAssignmentSet<Target, Tracker> =
			from(legacy.mapValues { (_, tracker) -> MainFallbackAssignment(main = tracker) })
	}
}
