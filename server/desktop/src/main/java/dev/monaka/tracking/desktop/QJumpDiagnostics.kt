package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.MtpPose
import dev.monaka.tracking.*
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.slimevr.tracking.processor.BoneType
import dev.slimevr.tracking.processor.skeleton.HumanSkeleton
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion

/** Copies normal-path evidence; never re-runs eligibility, requests pose getters, or owns tracking. */
class QJumpDiagnostics(val recorder: QJumpRecorder) : AutoCloseable {
	data class Pending(val tick: MonakaResolvedTickSnapshot, val samples: List<Map<String, Any?>>, val selected: Map<TrackerPosition, Map<String, String>>, val contexts: Map<TrackerPosition, Map<String, Any?>>, val richState: () -> Map<String, Map<String, Any?>>)
	private fun q(value: Quaternion?) = value?.let { listOf(it.x, it.y, it.z, it.w).map { n -> n.takeIf(Float::isFinite) } }
	private fun identity(p: MtpPose) = mapOf("publisher_id" to p.publisher_id, "source_id" to p.source_id, "tracker_id" to p.tracker_id,
		"session_id" to p.session_id, "clock_id" to p.clock_id, "sequence" to p.sequence.toString(), "mapping_revision" to p.mapping_revision.toString(),
		"space_id" to p.coordinate_space.id, "space_revision" to p.coordinate_space.revision.toString())
	private fun input(p: MtpPose) = mapOf("source_id" to p.input.source_id, "device_id" to p.input.device_id, "session_id" to p.input.session_id, "sequence" to p.input.sequence.toString())
	private fun sample(stage: String, lane: String, identity: Map<String, String>, rotation: Any?, context: Map<String, Any?>,
		validity: Map<String, Any?> = emptyMap(), timestamps: Map<String, Any?> = emptyMap(), links: Map<String, Any?> = mapOf("mvip" to identity)) =
		mapOf("stage" to stage, "stream_id" to lane, "identity" to identity, "q" to rotation, "context" to context, "validity" to validity, "timestamps" to timestamps, "links" to links)
	fun admitted(key: LogicalTracker, value: MtpObservationBackend.Sample, assignment: TrackerBodyAssignments.Snapshot) {
		val targets = assignment.targets.filter { (_, relation) -> key.observationId in listOf(relation.mainTracker.observationId, relation.rotationFallbackTracker?.observationId) }
		if (targets.isEmpty()) return
		val selectedSources = assignment.targets.values.flatMap { listOfNotNull(it.mainTracker, it.rotationFallbackTracker) }.distinct().take(16)
		if (selectedSources.none { it.observationId == key.observationId }) { recorder.dropped.incrementAndGet(); return }
		val p = value.pose
			recorder.offer(listOf(sample("admitted", "admitted:${key.observationId}", identity(p), p.orientation,
			mapOf("targets" to targets.keys.map { it.name }, "assignment_generation" to assignment.generation.toString(), "orientation_evidence" to p.input.orientation_evidence),
			mapOf("position" to p.validity.position, "rotation" to p.validity.orientation, "modality" to p.modality, "status" to p.tracking_state, "fresh" to null, "freshness_basis" to "ADMISSION_NOT_ELIGIBILITY"),
			mapOf("source_timestamp_ns" to p.timestamp_ns.toString(), "sent_at_ns" to p.sent_at_ns.toString(), "source_clock_domain" to p.clock_id, "timestamp_kind" to p.timestamp_kind,
				"receipt_ns" to value.receivedAtNanos.toString(), "derived_sample_ns" to value.sampleTime.toString(), "receipt_clock_domain" to "consumer_monotonic"),
			mapOf("mvip" to identity(p), "input" to input(p))) + mapOf("retention" to "TRIGGER_ONLY")))
	}
	fun before(tick: MonakaResolvedTickSnapshot, runtime: MonakaRuntime, poses: Map<TrackerPosition, ResolvedTrackingPose>, composition: MonakaSolverComposition): Pending {
		val targets = tick.assignment.targets.entries.take(16)
		if (tick.assignment.targets.size > targets.size) recorder.dropped.incrementAndGet()
		val selectedSources = targets.flatMap { listOfNotNull(it.value.mainTracker, it.value.rotationFallbackTracker) }.distinct().take(16)
		val accepted = selectedSources.mapNotNull { ref -> ref.mtp?.let { runtime.mtp.sampleFor(it) }?.let { ref.observationId to it } }.toMap()
		val proxies = composition.diagnosticProxySnapshot(includeOffsets = false).associateBy { it.target }
		val selected = linkedMapOf<TrackerPosition, Map<String, String>>()
		val contexts = linkedMapOf<TrackerPosition, Map<String, Any?>>()
		val samples = mutableListOf<Map<String, Any?>>()
		for ((target, relation) in targets) {
			val raw = tick.constraints[target]
			val pose = poses[target]
			val owner = pose?.rotationOwner ?: raw?.rotation?.sourceId ?: relation.mainTracker.observationId
			val admitted = accepted[owner]
			val id = admitted?.pose?.let(::identity) ?: emptyMap()
			selected[target] = id
			val proxy = proxies[target]
			val lifecycle = mapOf("assignment_generation" to tick.assignment.generation.toString(), "q_source" to relation.sourceSelection?.qSource?.observationId,
				"six_dof_source" to relation.sourceSelection?.sixDofSource?.observationId, "proxy_instance" to proxy?.instance, "proxy_name" to proxy?.stableName,
				"proxy_active" to proxy?.active, "topology_rebuilds" to proxy?.topologyRebuilds, "reset_epoch" to proxy?.resetState?.get("correction_reset_generation"))
			val context = mapOf("target" to target.name, "tick" to tick.tickSequence.toString(), "rotation_owner" to owner, "position_owner" to pose?.positionOwner,
				"use_as_ik_constraint" to relation.useAsIkConstraint, "output_mode" to relation.outputMode.name, "lifecycle" to lifecycle, "reset_state" to proxy?.resetState,
				"selection_basis" to "PINNED_TICK", "freshness_evaluation" to "NOT_REEVALUATED")
			contexts[target] = context
			val compact = mapOf("target" to target.name, "tick" to tick.tickSequence.toString(), "lifecycle" to lifecycle)
			val timestamps = mapOf("consumer_now_ns" to tick.nowNanos.toString(), "consumer_clock_domain" to "consumer_monotonic",
				"physical_receipt_cutoff_system_ns" to tick.trackerReceiptCutoffSystemNanos.toString())
			admitted?.let { acceptedSample ->
				val p = acceptedSample.pose
				samples += sample("admitted", "admitted_selected:${target.name}", identity(p), p.orientation, compact,
					mapOf("position" to p.validity.position, "rotation" to p.validity.orientation, "fresh" to (raw?.rotation != null), "modality" to p.modality, "status" to p.tracking_state),
					timestamps + mapOf("source_timestamp_ns" to p.timestamp_ns.toString(), "sent_at_ns" to p.sent_at_ns.toString(), "source_clock_domain" to p.clock_id,
						"receipt_ns" to acceptedSample.receivedAtNanos.toString(), "derived_sample_ns" to acceptedSample.sampleTime.toString(), "receipt_clock_domain" to "consumer_monotonic"),
					mapOf("mvip" to identity(p), "input" to input(p)))
			}
			samples += sample("resolved", "resolved:${target.name}", id, q(raw?.rotation?.value), compact + mapOf("rotation_owner" to owner, "position_owner" to pose?.positionOwner),
				mapOf("position" to (raw?.position != null), "rotation" to (raw?.rotation != null), "fresh" to (raw?.rotation != null)), timestamps)
			samples += sample("solver_constraint", "solver:${target.name}", id, q(proxy?.rawRotation), context,
				mapOf("position" to proxy?.hasPosition, "rotation" to proxy?.hasRotation, "active" to proxy?.active, "fresh" to (proxy?.active == true)), timestamps)
		}
		return Pending(tick, samples, selected, contexts) {
			composition.diagnosticProxySnapshot().associate { it.target.name to it.resetState }
		}
	}
	fun after(pending: Pending, skeleton: HumanSkeleton, consumed: List<HumanSkeleton.ConsumedRotationInput>, resetGeneration: Long) {
		val samples = pending.samples.toMutableList()
		for (read in consumed) {
			val target = read.target ?: continue
			val id = pending.selected[target] ?: continue
			samples += sample("consumed", "consumed:${target.name}:${read.readSite}", id,
				if (read.hasRotation) listOf(read.qx, read.qy, read.qz, read.qw).map { it?.takeIf(Float::isFinite) } else null,
				mapOf("lifecycle" to pending.contexts[target]?.get("lifecycle"), "target" to target.name, "read_site" to read.readSite, "tick" to read.tick.toString(), "proxy_stable_name" to read.trackerIdentity, "reset_generation" to resetGeneration.toString()),
				mapOf("position" to read.hasPosition, "rotation" to read.hasRotation, "fresh" to true))
		}
		for ((target, id) in pending.selected) {
			val bone = BoneType.values().firstOrNull { it.bodyPart == target.bodyPart } ?: continue
			samples += sample("bone", "bone:${target.name}:${bone.name}", id, q(skeleton.getBone(bone).getGlobalRotation()),
				mapOf("lifecycle" to pending.contexts[target]?.get("lifecycle"), "target" to target.name, "name" to bone.name, "tick" to pending.tick.tickSequence.toString(), "reset_generation" to resetGeneration.toString()), mapOf("fresh" to true))
		}
		for (tracker in skeleton.humanPoseManager.computedTrackers) {
			val target = tracker.trackerPosition ?: continue
			// Derived outputs retain all actual selected links; no unique upstream cause is invented.
			samples += sample("computed", "computed:${target.name}", emptyMap(), q(tracker.getRawRotation()),
				mapOf("target" to target.name, "name" to tracker.name, "tick" to pending.tick.tickSequence.toString(), "reset_generation" to resetGeneration.toString()),
				mapOf("fresh" to true), links = mapOf("consumer_frame" to mapOf("tick" to pending.tick.tickSequence.toString(), "assignment_generation" to pending.tick.assignment.generation.toString(), "selected_target_count" to pending.selected.size.toString())))
		}
		if (samples.size > 256) recorder.dropped.incrementAndGet()
		recorder.offer(samples.take(256)) { retained ->
			// Called synchronously only for an actual retained/trigger frame, never for past observations.
			val states = pending.richState()
			val observedSystemNanos = System.nanoTime().toString()
			retained.map { sample ->
				if (sample["stage"] != "solver_constraint") sample else {
					@Suppress("UNCHECKED_CAST") val context = sample["context"] as Map<String, Any?>
					sample + mapOf("context" to (context + mapOf("reset_state" to states[context["target"]], "reset_state_snapshot_phase" to "POST_FK_SAME_TICK",
						"reset_state_observed_system_ns" to observedSystemNanos, "reset_state_observation_clock_domain" to "system_nanotime")))
				}
			}
		}
	}
	fun reset(state: dev.slimevr.tracking.processor.FullResetDiagnostics.Snapshot) = recorder.reset(state)
	override fun close() = recorder.close()
	companion object { fun fromEnvironment() = QJumpRecorder.fromEnvironment()?.let(::QJumpDiagnostics) }
}
