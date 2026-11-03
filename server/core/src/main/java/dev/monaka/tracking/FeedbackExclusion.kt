package dev.monaka.tracking

import dev.slimevr.tracking.trackers.Tracker

/** Process-local output ownership only. Opaque identity spelling never establishes origin. */
object FeedbackExclusion {
	// Tracker is final and uses reference equality. Weak keys retain the classification while any
	// caller can still reuse the object, without keeping closed owners or old trackers alive.
	private val ownedOutputs = java.util.WeakHashMap<Tracker, Boolean>()

	@Synchronized internal fun registerOwnedOutput(tracker: Tracker) {
		ownedOutputs[tracker] = true
	}

	@Synchronized internal fun isOwnedOutput(tracker: Tracker): Boolean = ownedOutputs.containsKey(tracker)

	internal fun isOutputTracker(tracker: Tracker): Boolean =
		isOwnedOutput(tracker) || tracker.monakaOutputPose != null || (tracker.isInternal && tracker.isComputed)

	fun accepts(tracker: Tracker): Boolean = !isOutputTracker(tracker)
}
