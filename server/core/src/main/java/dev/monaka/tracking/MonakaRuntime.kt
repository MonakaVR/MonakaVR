package dev.monaka.tracking

import dev.monaka.protocol.v1.CoordinateSpace
import dev.monaka.tracking.mtp.*
import dev.slimevr.tracking.trackers.Tracker

/** One clock, profile registry, runner, assignment registry and pipeline for every input. */
class MonakaRuntime(
	trackers: () -> Iterable<Tracker>,
	expectedSpace: CoordinateSpace,
	val assignments: TrackerBodyAssignments = TrackerBodyAssignments(),
	val inbox: MtpInbox = MtpInbox(),
	val clock: () -> Long = monotonicClock(),
	timeoutNanos: Long = 500_000_000,
) : AutoCloseable {
	val profiles = ObservationSourceProfileRegistry(listOf(
		ObservationSourceProfile.sixDof("slime", 0, Long.MAX_VALUE, Long.MAX_VALUE),
		ObservationSourceProfile.sixDof("mtp", 100, timeoutNanos, timeoutNanos),
	))
	val pipeline = ConstraintPipeline(profileRegistry = profiles)
	val mtp = MtpObservationBackend(inbox, assignments, expectedSpace)
	val runner = ObservationBackendRunner(pipeline, listOf(
		SlimeTrackerObservationBackend("slime", "slime", { trackers().filter(FeedbackExclusion::accepts) }), mtp,
	))
	private var closed = false
	private var wasPaused = false
	fun tick(paused: Boolean = false): Map<dev.slimevr.tracking.trackers.TrackerPosition, EffectiveConstraint> {
		check(!closed)
		val now = clock()
		// Drain paused traffic with its sequence watermarks, then discard its samples on either edge.
		if (paused != wasPaused) { mtp.suspend(true); runner.invalidate("mtp") }
		for (backend in runner.snapshot().keys) {
			try { runner.poll(backend, now) } catch (_: Exception) {
				runner.invalidate(backend)
				if (backend == "mtp") mtp.invalidateSamples()
				inbox.count("BackendFailure:$backend")
			}
		}
		if (!paused && wasPaused) { inbox.clear(); mtp.suspend(false) }
		wasPaused = paused
		return pipeline.resolveAll(now)
	}
	override fun close() {
		if (closed) return
		mtp.close(); runner.clear(); pipeline.clear(); profiles.clear(); closed = true
	}
	companion object {
		fun monotonicClock(): () -> Long {
			val epoch = System.nanoTime()
			return { (System.nanoTime() - epoch).coerceAtLeast(0) }
		}
	}
}
