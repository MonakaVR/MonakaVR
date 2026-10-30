package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion

/** Opt-in eligibility filter before the existing resolver/policy; legacy Slime profiles stay unchanged. */
class AssignedImuSampleFreshness(
	private val assignments: TrackerBodyAssignments,
	private val maxAgeNanos: Long,
	private val onlyExplicitPairs: Boolean = false,
	private val legacyHipMaxAgeNanos: Long? = null,
) {
	init {
		require(maxAgeNanos >= 0 && (legacyHipMaxAgeNanos == null || legacyHipMaxAgeNanos >= 0))
	}
	private data class Epoch(
		val source: String,
		val sourceEpoch: String,
		val calibrationEpoch: String,
		val mappingRevision: Long?,
		val space: CoordinateSpace?,
		val assignmentGeneration: Long,
	)
	private data class Cursor(val epoch: Epoch, val sequence: Long, val sampleAt: Long)
	private val cursors = linkedMapOf<String, Cursor>()

	fun apply(observation: PoseObservation, now: Long): PoseObservation = apply(observation, now, assignments.snapshot())

	fun apply(observation: PoseObservation, now: Long, assignment: TrackerBodyAssignments.Snapshot): PoseObservation {
		val relation = assignment.targets[observation.target]
		// Explicit local Q includes Q-only Main. MVIP keeps its existing source profile.
		val selected = relation?.sourceSelection?.qSource ?: relation?.rotationFallbackTracker?.takeUnless { onlyExplicitPairs }
		if (selected == null || selected.mtp != null ||
			observation.sourceId != selected.observationId ||
			!observation.rotationQuality.usable
		) {
			return observation
		}
		val provenance = observation.provenance ?: return observation.copy(rotationQuality = ObservationQuality.STALE)
		val sampleAt = provenance.sampleAtNanos
		val maxAge = legacyHipMaxAgeNanos?.takeIf { observation.target == TrackerPosition.HIP && relation?.rotationFallbackTracker == selected } ?: maxAgeNanos
		val fresh = sampleAt <= now &&
			now - sampleAt <= maxAge &&
			validRotation(observation.rotation)
		if (!fresh) return observation.copy(rotationQuality = ObservationQuality.STALE)
		val currentEpoch = Epoch(
			observation.sourceId,
			provenance.sourceEpoch,
			provenance.calibrationEpoch,
			provenance.mappingRevision,
			provenance.space,
			assignment.generation,
		)
		val old = cursors[observation.sourceId]?.takeIf { it.epoch == currentEpoch }
		if (old != null &&
			(
				provenance.sequence < old.sequence ||
					(provenance.sequence == old.sequence && sampleAt != old.sampleAt) ||
					(provenance.sequence > old.sequence && sampleAt <= old.sampleAt)
				)
		) {
			return observation.copy(rotationQuality = ObservationQuality.STALE)
		}
		cursors[observation.sourceId] = Cursor(currentEpoch, provenance.sequence, sampleAt)
		return observation
	}

	private fun validRotation(q: Quaternion?): Boolean = q != null &&
		listOf(q.w, q.x, q.y, q.z).all { it.isFinite() } &&
		q.lenSq().isFinite() &&
		q.lenSq() > 1e-10f
}
