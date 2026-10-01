package dev.monaka.tracking

import dev.slimevr.tracking.trackers.TrackerPosition

/** Opt-in eligibility filter before the existing resolver/policy; legacy Slime profiles stay unchanged. */
class AssignedImuSampleFreshness(
	private val assignments: TrackerBodyAssignments,
	private val maxAgeNanos: Long,
) {
	init { require(maxAgeNanos > 0) }

	fun apply(observation: PoseObservation, now: Long): PoseObservation {
		val fallback = assignments.snapshot().targets[TrackerPosition.HIP]?.rotationFallbackTracker?.observationId
		if (observation.target != TrackerPosition.HIP || observation.sourceId != fallback ||
			!observation.rotationQuality.usable) return observation
		val sampleAt = observation.provenance?.sampleAtNanos
		val fresh = sampleAt != null && sampleAt <= now && now - sampleAt <= maxAgeNanos
		return if (fresh) observation else observation.copy(rotationQuality = ObservationQuality.STALE)
	}
}
