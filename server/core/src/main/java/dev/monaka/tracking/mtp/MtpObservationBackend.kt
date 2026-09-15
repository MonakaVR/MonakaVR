package dev.monaka.tracking.mtp

import dev.monaka.protocol.v1.*
import dev.monaka.tracking.*

/** Server-thread incremental cache. Watermarks survive loss/pause; pose age never changes. */
class MtpObservationBackend(
	private val inbox: MtpInbox,
	private val assignments: TrackerBodyAssignments,
	private val expectedSpace: CoordinateSpace,
	override val backendId: String = "mtp",
	override val profileId: String = "mtp",
) : ObservationBackend {
	data class Sample(val pose: MtpPose, val sampleTime: Long)
	private class Lifetime(val session: String, val clock: String, val retired: MutableSet<String>) {
		var quarantined = false
	}
	private class Device {
		var poseSequence = -1L
		var stateSequence = -1L
		var mappingRevision = -1L
		var absentAt = -1L
		var sample: Sample? = null
	}
	private val lifetimes = linkedMapOf<String, Lifetime>()
	private val devices = linkedMapOf<LogicalTracker, Device>()
	private val removed = linkedSetOf<String>()
	private val adapter = MtpPoseAdapter()
	private var assignmentGeneration = -1L
	var historyGeneration = 0L
		private set
	var suspended = false
		private set

	init { require(expectedSpace.convention == "rh_y_up_neg_z_forward") }
	fun samples(): Map<LogicalTracker, Sample> = devices.mapNotNull { (k, d) -> d.sample?.let { k to it } }.toMap()
	fun suspend(value: Boolean) {
		if (suspended != value) {
			invalidateSamples()
			suspended = value
		}
	}
	/** Retain sequence/session tombstones so old packets cannot resurrect cleared constraints. */
	fun invalidateSamples() {
		if (devices.values.any { it.sample != null }) historyGeneration++
		for ((key, device) in devices) { device.sample = null; removed += key.observationId }
	}
	fun close() {
		invalidateSamples(); inbox.clear(); devices.clear(); lifetimes.clear(); assignmentGeneration = -1
	}
	override fun drainRemovedSources(): Set<String> = removed.toSet().also { removed.clear() }

	override fun poll(observedAtNanos: Long): List<PoseObservation> {
		val dirty = linkedSetOf<LogicalTracker>()
		for (message in inbox.drain()) admit(message, dirty)
		val assignment = assignments.snapshot()
		if (assignment.generation != assignmentGeneration) {
			dirty += devices.keys
			removed += devices.keys.map { it.observationId }
			assignmentGeneration = assignment.generation
		}
		return dirty.mapNotNull { key ->
			// Replacement follows sequence admission, not the local timestamp ordering in ObservationStore.
			removed += key.observationId
			val sample = devices[key]?.sample ?: return@mapNotNull null
			val target = assignment.entries[key] ?: run { inbox.count("UnassignedPose"); return@mapNotNull null }
			try { adapter.adapt(sample.pose, target, sample.sampleTime) } catch (_: IllegalArgumentException) {
				devices.getValue(key).sample = null
				inbox.count("FloatOverflow"); null
			}
		}
	}

	private fun removeSource(source: String) {
		if (devices.any { (key, device) -> key.sourceId == source && device.sample != null }) historyGeneration++
		for (key in devices.keys.filter { it.sourceId == source }) {
			removed += key.observationId
			devices.getValue(key).sample = null
		}
	}
	private fun admit(received: MtpInbox.Received, dirty: MutableSet<LogicalTracker>) {
		val pose = received.envelope as? MtpPose
		val state = received.envelope as? MtpTrackerState
		val key = if (pose != null) LogicalTracker(pose.source_id, pose.tracker_id)
			else LogicalTracker(requireNotNull(state).source_id, state.tracker_id)
		if (key.isFeedback || (pose != null && FeedbackExclusion.isOutput(pose.input.device_id))) {
			inbox.count("FeedbackExcluded"); return
		}
		val session = pose?.session_id ?: state!!.session_id
		val clock = pose?.clock_id ?: state!!.clock_id
		var lifetime = lifetimes[key.sourceId]
		if (lifetime == null) {
			if (lifetimes.size >= 64) { inbox.count("SourceLimit"); return }
			lifetime = Lifetime(session, clock, linkedSetOf())
			lifetimes[key.sourceId] = lifetime
		} else {
			if (lifetime.quarantined || session in lifetime.retired) { inbox.count("RetiredSession"); return }
			if (session != lifetime.session) {
				removeSource(key.sourceId)
				// Do not evict retired UUIDs and accidentally allow replay. Saturation fails closed.
				if (lifetime.retired.size >= 256) {
					lifetime.quarantined = true; inbox.count("SessionLimit"); return
				}
				lifetime.retired += lifetime.session
				lifetimes[key.sourceId] = Lifetime(session, clock, lifetime.retired)
				devices.keys.filter { it.sourceId == key.sourceId }.forEach { devices.remove(it) }
				inbox.count("SessionChanged")
			} else if (clock != lifetime.clock) {
				removeSource(key.sourceId); inbox.count("ClockMismatch"); return
			}
		}
		if (key !in devices && devices.size >= 1024) { inbox.count("TrackerLimit"); return }
		val device = devices.getOrPut(key) { Device() }
		val sequence = pose?.sequence ?: state!!.sequence
		val previous = if (pose != null) device.poseSequence else device.stateSequence
		if (sequence <= previous) { inbox.count("DuplicateOrOldSequence"); return }
		if (pose != null) device.poseSequence = sequence else device.stateSequence = sequence
		val space = pose?.coordinate_space ?: state!!.coordinate_space
		if (space != expectedSpace) {
			removeSource(key.sourceId); inbox.count("SpaceMismatch"); return
		}
		val revision = pose?.mapping_revision ?: state!!.mapping_revision
		if (revision < device.mappingRevision) { inbox.count("OldMappingRevision"); return }
		if (revision > device.mappingRevision) {
			historyGeneration++
			device.sample = null; removed += key.observationId; device.mappingRevision = revision
		}
		if (pose == null) {
			if (state!!.presence == "absent" || state.tracking_state in listOf("lost", "disconnected")) {
				if (device.sample != null) historyGeneration++
				device.sample = null; removed += key.observationId
				device.absentAt = maxOf(device.absentAt, state.timestamp_ns)
			}
			return // Present/battery heartbeat cannot repair or refresh a pose.
		}
		dirty += key
		device.sample = null
		if (suspended || pose.timestamp_ns <= device.absentAt) return
		val age = pose.sent_at_ns - pose.timestamp_ns // Both U63, codec checked timestamp <= sent_at.
		if (age > received.receivedAtNanos) { inbox.count("BeforeLocalEpoch"); return }
		device.sample = Sample(pose, received.receivedAtNanos - age)
		inbox.count("PoseAccepted")
	}
}
