package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion

/** Opt-in eligibility filter before the existing resolver/policy; legacy Slime profiles stay unchanged. */
class AssignedImuSampleFreshness(
	private val assignments: TrackerBodyAssignments,
	private val maxAgeNanos: Long,
) {
	init { require(maxAgeNanos > 0) }
	private data class Epoch(val source: String, val sourceEpoch: String, val calibrationEpoch: String,
		val mappingRevision: Long?, val space: CoordinateSpace?, val assignmentGeneration: Long)
	private var epoch: Epoch? = null
	private var lastSequence: Long? = null
	private var lastSampleAt: Long? = null

	fun apply(observation: PoseObservation, now: Long): PoseObservation {
		return apply(observation, now, assignments.snapshot())
	}

	fun apply(observation: PoseObservation, now: Long, assignment: TrackerBodyAssignments.Snapshot): PoseObservation {
		val fallback = assignment.targets[TrackerPosition.HIP]?.rotationFallbackTracker?.observationId
		if (observation.target != TrackerPosition.HIP || observation.sourceId != fallback ||
			!observation.rotationQuality.usable) return observation
		val provenance = observation.provenance ?: return observation.copy(rotationQuality = ObservationQuality.STALE)
		val sampleAt = provenance.sampleAtNanos
		val fresh = sampleAt <= now && now - sampleAt <= maxAgeNanos &&
			validRotation(observation.rotation) && validRotation(observation.correctionRotation)
		if (!fresh) return observation.copy(rotationQuality = ObservationQuality.STALE)
		val currentEpoch = Epoch(observation.sourceId, provenance.sourceEpoch, provenance.calibrationEpoch,
			provenance.mappingRevision, provenance.space, assignment.generation)
		if (epoch != currentEpoch) {
			epoch = currentEpoch; lastSequence = null; lastSampleAt = null
		}
		if (lastSequence != null && (provenance.sequence < lastSequence!! ||
			(provenance.sequence == lastSequence && sampleAt != lastSampleAt) ||
			(provenance.sequence > lastSequence!! && sampleAt <= lastSampleAt!!)))
			return observation.copy(rotationQuality = ObservationQuality.STALE)
		lastSequence = provenance.sequence; lastSampleAt = sampleAt
		return observation
	}

	private fun validRotation(q: Quaternion?): Boolean = q != null &&
		listOf(q.w, q.x, q.y, q.z).all { it.isFinite() } && q.lenSq().isFinite() && q.lenSq() > 1e-10f
}
