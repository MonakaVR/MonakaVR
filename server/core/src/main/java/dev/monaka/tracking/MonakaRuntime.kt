package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.mtp.*
import dev.slimevr.tracking.trackers.Tracker

/** One clock, profile registry, runner, assignment registry and pipeline for every input. */
class MonakaRuntime(
	trackers: () -> Iterable<Tracker>,
	val expectedSpace: CoordinateSpace,
	val assignments: TrackerBodyAssignments = TrackerBodyAssignments(),
	val inbox: MtpInbox = MtpInbox(),
	val clock: () -> Long = monotonicClock(),
	timeoutNanos: Long = 500_000_000,
	maxImuSampleAgeNanos: Long? = null,
	private val trackerReceiptClock: () -> Long = System::nanoTime,
) : AutoCloseable {
	private val assignedImuFreshness = maxImuSampleAgeNanos?.let { AssignedImuSampleFreshness(assignments, it) }
	val profiles = ObservationSourceProfileRegistry(listOf(
		ObservationSourceProfile.sixDof("slime", 0, Long.MAX_VALUE, Long.MAX_VALUE),
		ObservationSourceProfile.sixDof("mtp", 100, timeoutNanos, timeoutNanos),
	))
	val pipeline = ConstraintPipeline(profileRegistry = profiles, resolver = ConstraintResolver { assignments.snapshot().targets },
		eligibility = { observation, now -> assignedImuFreshness?.apply(observation, now) ?: observation },
		pinnedEligibility = { observation, now, assignment -> assignedImuFreshness?.apply(observation, now, assignment) ?: observation })
	val mtp = MtpObservationBackend(inbox, assignments, expectedSpace)
	val runner = ObservationBackendRunner(pipeline, listOf(
		SlimeTrackerObservationBackend("slime", "slime", { trackers().filter(FeedbackExclusion::accepts) }, assignments = { assignments.snapshot().targets }), mtp,
	))
	private var closed = false
	private var wasPaused = false
	private var nextTickSequence = 0L
	private var tickSequenceExhausted = false
	private var lastCompletedTick: MonakaResolvedTickSnapshot? = null
	var lastTickNanos: Long = 0
		private set
	fun tick(paused: Boolean = false): Map<dev.slimevr.tracking.trackers.TrackerPosition, EffectiveConstraint> =
		tickSnapshot(paused).constraints

	/** Server-thread tick: assignment changes after capture become visible on the next tick. */
	fun tickSnapshot(paused: Boolean = false): MonakaResolvedTickSnapshot {
		check(!closed)
		check(!tickSequenceExhausted) { "Runtime tick sequence exhausted" }
		val sequence = nextTickSequence
		if (sequence == Long.MAX_VALUE) tickSequenceExhausted = true else nextTickSequence++
		// Revoke the old capture seam even if this reserved tick fails partway through.
		lastCompletedTick = null
		val receiptCutoff = trackerReceiptClock()
		val now = clock()
		require(now >= 0) { "Runtime tick clock must be non-negative" }
		lastTickNanos = now
		val assignment = assignments.snapshot()
		val resuming = !paused && wasPaused
		// Drain paused traffic with its sequence watermarks, then discard its samples on either edge.
		if (paused != wasPaused) { mtp.suspend(true); runner.invalidate("mtp") }
		val backendIds = runner.snapshot().keys.toList()
		for (backend in backendIds) {
			if (backend == "mtp" && resuming) continue
			try { runner.poll(backend, now, assignment) } catch (_: Exception) {
				runner.invalidate(backend)
				if (backend == "mtp") mtp.invalidateSamples()
				inbox.count("BackendFailure:$backend")
			}
		}
		if (resuming) {
			// Capacity bounds this one transition. Every queued packet reaches server-owned
			// lifetime state; none can become a post-resume constraint.
			mtp.discardQueuedWhileUpdatingWatermarks()
			mtp.suspend(false)
		}
		wasPaused = paused
		return MonakaResolvedTickSnapshot(sequence, now, now, paused, assignment,
			pipeline.resolveAll(now, assignment), receiptCutoff).also { lastCompletedTick = it }
	}

	/** Server-thread read-only seam. Only this runtime's latest completed tick has authority. */
	internal fun captureSourceSnapshot(tick: MonakaResolvedTickSnapshot): MonakaSourceTickSnapshot {
		check(!closed && tick === lastCompletedTick && tick.nowNanos == lastTickNanos) { "Source capture requires latest completed runtime tick" }
		return MonakaSourceTickSnapshot(tick.tickSequence, tick.nowNanos, tick.assignment,
			pipeline.observations(tick.nowNanos, tick.assignment).associateBy { it.sourceId },
			runner.sourceOwnersSnapshot(), mtp.sourceContexts())
	}

	/** Wrap the already-selected components. Fresh Main metadata is diagnostic only. */
	fun resolvedTrackingPoses(constraints: Map<dev.slimevr.tracking.trackers.TrackerPosition, EffectiveConstraint>): Map<dev.slimevr.tracking.trackers.TrackerPosition, ResolvedTrackingPose> {
		val assignments = assignments.snapshot().targets
		val observations = pipeline.observations(lastTickNanos).associateBy { it.sourceId }
		return (constraints.keys + assignments.keys).associateWith { target ->
			ResolvedTrackingPose.from(constraints[target] ?: EffectiveConstraint(target), expectedSpace,
				assignments[target]?.mainTracker?.observationId?.let(observations::get))
		}
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
