package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.correction.BodyRotationGuidance
import dev.monaka.tracking.correction.BodyRotationResult
import dev.monaka.tracking.correction.BodyRotationState
import dev.monaka.tracking.correction.GuidanceBinding
import dev.monaka.tracking.correction.GuidanceEpoch
import dev.monaka.tracking.correction.GuidanceEvidence
import dev.monaka.tracking.correction.GuidanceFrames
import dev.monaka.tracking.correction.GuidanceIdentity
import dev.monaka.tracking.correction.GuidanceImu
import dev.monaka.tracking.correction.GuidanceInput
import dev.monaka.tracking.correction.GuidanceOrigin
import dev.monaka.tracking.correction.GuidanceReceipt
import dev.monaka.tracking.correction.GuidanceSample
import dev.monaka.tracking.correction.GuidanceSource
import dev.monaka.tracking.correction.GuidanceTeacher
import dev.monaka.tracking.revision.RotationOwner
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion

/** Server-owned connection of the pure learner to independently accepted input samples.
 * Does not change source selection, create positions, or publish observations.
 */
internal class BodyRotationRuntime(
	private val space: CoordinateSpace,
	private val tuning: RotationCorrectionTuning,
	private val excludedTargets: Set<TrackerPosition>,
	private val reacquireDurationNanos: Long,
) {
	private data class Recovery(var last: Quaternion? = null, var fallback: Boolean = false, var start: Quaternion? = null, var at: Long = 0, var semantic: List<Any?>? = null, var mainEpoch: dev.monaka.tracking.mtp.MtpSourceContextSnapshot? = null)
	private val recovery = linkedMapOf<TrackerPosition, Recovery>()
	private val states = linkedMapOf<TrackerPosition, BodyRotationState>()
	var results: Map<TrackerPosition, BodyRotationResult> = emptyMap()
		private set

	fun apply(
		tick: MonakaResolvedTickSnapshot,
		sources: MonakaSourceTickSnapshot,
		poses: Map<TrackerPosition, ResolvedTrackingPose>,
		capabilities: Map<LogicalTracker, dev.monaka.tracking.mtp.MtpObservationBackend.CapabilitySnapshot>,
	): Map<TrackerPosition, ResolvedTrackingPose> {
		if (tick.paused) {
			states.clear()
			recovery.clear()
			results = emptyMap()
			return poses
		}
		val active = tick.assignment.targets.filter { (target, relation) ->
			target !in excludedTargets &&
				relation.sourceSelection?.let {
					it.sixDofSource != null && it.qSource != null && it.qSource != it.sixDofSource
				} == true
		}
		states.keys.retainAll(active.keys)
		val updated = poses.toMutableMap()
		val diagnostics = linkedMapOf<TrackerPosition, BodyRotationResult>()
		for ((target, relation) in active) {
			val mainRef = relation.mainTracker
			val qRef = requireNotNull(relation.rotationFallbackTracker)
			val main = sources.observationsBySource[mainRef.observationId]
			val q = sources.observationsBySource[qRef.observationId]
			val previous = states[target] ?: BodyRotationState()
			// Physical Q has independently captured orientation + receipt. An MVIP
			// pose's orientation is never relabeled as an independent physical IMU.
			val physicalQ = qRef.mtp == null && sources.sourceOwners[qRef.observationId] == "slime" && q?.correctionRotation != null
			val mainContext = mainRef.mtp?.let(sources.mtpContexts::get)
			val mainSource = main?.takeIf { sources.sourceOwners[it.sourceId] == "mtp" && mainContext != null }
				?.let { source(mainRef, it.provenance) } ?: previous.binding?.main?.takeIf { it.identity == identity(mainRef) }
			val imuSource = q?.takeIf { physicalQ }?.let { source(qRef, it.provenance) }
				?: previous.binding?.imu?.takeIf { it.identity == identity(qRef) }
			val binding = if (mainSource != null && imuSource != null) {
				GuidanceBinding(
					target.name,
					tick.assignment.generation,
					mainSource,
					imuSource,
					// MVIP is already mapped; physical Q capture includes its saved
					// reset/mount transform. Uncalibrated extra transforms are Identity.
					GuidanceFrames(Quaternion.IDENTITY, Quaternion.IDENTITY, "", false, mainSource, imuSource),
				)
			} else {
				null
			}
			val teacherSample = main?.takeIf { mainContext != null }?.let { sample(target, mainRef, it, GuidanceOrigin.INDEPENDENT_MAIN_ACCEPTANCE, mainRef.mtp?.let(sources.mtpSamples::get)) }
			val imuSample = q?.takeIf { physicalQ }?.let { sample(target, qRef, it, GuidanceOrigin.PHYSICAL_IMU_ACCEPTANCE) }
			val selected = poses[target]
			val result = BodyRotationGuidance.advance(
				previous,
				GuidanceInput(
					tick.nowNanos,
					binding,
					teacherSample?.let {
						GuidanceTeacher(
							it,
							main!!.modality,
							main.position,
							mainRef.mtp?.let { capabilities[it]?.capabilities },
							main.positionQuality.usable,
							main.rotationQuality.usable,
						)
					},
					imuSample?.let { GuidanceImu(it, q!!.rotationQuality.usable) },
					when (selected?.rotationOwner) {
						qRef.observationId -> RotationOwner.FALLBACK
						mainRef.observationId -> RotationOwner.MAIN
						else -> RotationOwner.NONE
					},
				),
				tuning,
			)
			states[target] = result.state
			diagnostics[target] = result
			val corrected = result.fallbackCandidate
			if (corrected != null && selected?.rotationOwner == qRef.observationId && q != null) {
				updated[target] = selected.copy(rotation = ResolvedComponent(corrected.orientation, q.sourceId, q.rotationQuality, corrected.sampleAtNanos))
			}
		}
		results = diagnostics.toMap()
		return updated
	}

	/** Shared by normal and historical configured learners, after their single
	 * corrected component is selected. Both solver and visible output see this value.
	 */
	fun recover(tick: MonakaResolvedTickSnapshot, sources: MonakaSourceTickSnapshot, poses: Map<TrackerPosition, ResolvedTrackingPose>): Map<TrackerPosition, ResolvedTrackingPose> {
		if (tick.paused) {
			recovery.clear()
			return poses
		}
		val updated = poses.toMutableMap()
		val active = tick.assignment.targets.filterValues { it.rotationFallbackTracker != null }
		recovery.keys.retainAll(active.keys)
		for ((target, relation) in active) {
			val mainRef = relation.mainTracker
			val qRef = requireNotNull(relation.rotationFallbackTracker)
			var r = recovery.getOrPut(target) { Recovery() }
			val main = mainRef.mtp?.let(sources.mtpContexts::get)
			val q = sources.observationsBySource[qRef.observationId]?.provenance
			val mainEpoch = main ?: r.mainEpoch
			val semantic = listOf(tick.assignment.generation, relation, mainEpoch, q?.sourceEpoch, q?.calibrationEpoch, q?.mappingRevision, q?.space)
			if (r.semantic != null && r.semantic != semantic) {
				r = Recovery().also { recovery[target] = it }
			}
			r.semantic = semantic
			r.mainEpoch = mainEpoch
			val current = updated[target]
			val rotation = current?.rotation
			if (rotation != null) {
				if (current.rotationOwner == mainRef.observationId && current.main.modality == TrackingModality.FULL && current.main.positionValid && r.fallback && r.last != null) {
					r.start = r.last
					r.at = tick.nowNanos
				}
				r.fallback = current.rotationOwner == qRef.observationId
				if (r.fallback || current.main.modality != TrackingModality.FULL) r.start = null
				val start = r.start
				if (start != null) {
					val fraction = ((tick.nowNanos - r.at).toDouble() / reacquireDurationNanos).coerceIn(0.0, 1.0).toFloat()
					updated[target] = current.copy(rotation = rotation.copy(value = start.interpR(rotation.value, fraction).unit()))
					if (fraction == 1f) r.start = null
				}
				r.last = updated[target]?.rotation?.value
			} else {
				recovery.remove(target)
			}
		}
		return updated
	}

	private fun identity(ref: TrackerReference) = ref.mtp?.let {
		GuidanceIdentity(it.publisherId, it.sourceId, it.trackerId)
	} ?: GuidanceIdentity("local-input", "physical-imu", ref.observationId)

	private fun source(ref: TrackerReference, p: ObservationSampleProvenance?): GuidanceSource? = p?.let {
		GuidanceSource(
			identity(ref),
			GuidanceEpoch(
				it.sourceEpoch,
				it.sourceEpoch,
				it.calibrationEpoch,
				it.mappingRevision ?: 0,
				it.space ?: space,
			),
		)
	}

	private fun sample(target: TrackerPosition, ref: TrackerReference, pose: PoseObservation, origin: GuidanceOrigin, mtpSample: dev.monaka.tracking.mtp.MtpObservationBackend.Sample? = null): GuidanceSample? {
		val p = pose.provenance ?: return null
		val source = source(ref, p) ?: return null
		val q = pose.correctionRotation ?: return null
		// sampleAt is mapped once by its accepting backend, never refreshed by poll.
		val receivedAt = if (ref.mtp == null) {
			p.sampleAtNanos
		} else {
			mtpSample?.takeIf {
				it.pose.sequence == p.sequence && it.sampleTime == p.sampleAtNanos
			}?.receivedAtNanos ?: return null
		}
		val receipt = GuidanceReceipt(p.sequence, p.sampleAtNanos, receivedAt, source.epoch)
		return GuidanceSample(target.name, source.identity, q, receipt, GuidanceEvidence(origin, source.identity, receipt, q))
	}
}
