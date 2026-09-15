package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker

/** Serial namespaces are transport-independent; real computed HMD inputs remain valid. */
object FeedbackExclusion {
	fun isOutput(identity: String): Boolean = listOf(
		"monaka-direct:", "monaka-solver:", "monaka-private:", "human://",
	).any(identity::startsWith)

	fun accepts(tracker: Tracker): Boolean =
		!isOutput(tracker.name) && !(tracker.isInternal && tracker.isComputed)
}
