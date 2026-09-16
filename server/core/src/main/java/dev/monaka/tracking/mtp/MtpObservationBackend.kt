package dev.monaka.tracking.mtp

import dev.monaka.protocol.v2.*
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
	private class Lifetime(val session: String, val clock: String, val peer: String, val retired: MutableSet<String>) {
		var quarantined = false
        var lastAccepted = -1L
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

	/**
	 * Resume-transition drain: advance replay/session/mapping watermarks for the
	 * current bounded queue while [suspended] prevents every pose becoming a sample.
	 */
	fun discardQueuedWhileUpdatingWatermarks(): Int {
		check(suspended) { "resume backlog must be discarded while MTP is suspended" }
		val discarded = inbox.drainAllBounded()
		val ignoredDirtySources = linkedSetOf<LogicalTracker>()
		for (message in discarded) admit(message, ignoredDirtySources)
		return discarded.size
	}

	val resumeDrainBound: Int
		get() = inbox.resumeDrainBound
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
		if (devices.any { (key, device) -> key.lifetimeId == source && device.sample != null }) historyGeneration++
		for (key in devices.keys.filter { it.lifetimeId == source }) {
			removed += key.observationId
			devices.getValue(key).sample = null
		}
	}
	private fun admit(received: MtpInbox.Received, dirty: MutableSet<LogicalTracker>) {
		val pose = received.envelope as? MtpPose
		val state = received.envelope as? MtpTrackerState
		val key = if (pose != null) LogicalTracker(pose.source_id, pose.tracker_id, pose.publisher_id)
			else LogicalTracker(requireNotNull(state).source_id, state.tracker_id, state.publisher_id)
		if (key.isFeedback || (pose != null && FeedbackExclusion.isOutput(pose.input.device_id))) {
			inbox.count("FeedbackExcluded"); return
		}
		val session = pose?.session_id ?: state!!.session_id
		val clock = pose?.clock_id ?: state!!.clock_id
		var lifetime = lifetimes[key.lifetimeId]
		if (lifetime == null) {
			if (lifetimes.size >= 64) { inbox.count("SourceLimit"); return }
			lifetime = Lifetime(session, clock, received.peer, linkedSetOf())
			lifetimes[key.lifetimeId] = lifetime
		} else {
			if (lifetime.quarantined || session in lifetime.retired) { inbox.count("RetiredSession"); return }
			if (session == lifetime.session && received.peer != lifetime.peer) { inbox.count("PeerMismatch"); return }
            if (session != lifetime.session) {
                if (received.receivedAtNanos - lifetime.lastAccepted < 500_000_000) { inbox.count("ActiveLease"); return }
				removeSource(key.lifetimeId)
				// Do not evict retired UUIDs and accidentally allow replay. Saturation fails closed.
				if (lifetime.retired.size >= 256) {
					lifetime.quarantined = true; inbox.count("SessionLimit"); return
				}
				lifetime.retired += lifetime.session
				lifetimes[key.lifetimeId] = Lifetime(session, clock, received.peer, lifetime.retired)
				devices.keys.filter { it.lifetimeId == key.lifetimeId }.forEach { devices.remove(it) }
				inbox.count("SessionChanged")
			} else if (clock != lifetime.clock) {
				devices[key]?.let { it.sample = null }; removed += key.observationId
                inbox.count("ClockMismatch"); return
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
			if (device.sample != null) historyGeneration++
			device.sample = null; removed += key.observationId; inbox.count("SpaceMismatch"); return
		}
		val revision = pose?.mapping_revision ?: state!!.mapping_revision
		if (revision < device.mappingRevision) { inbox.count("OldMappingRevision"); return }
		if (revision > device.mappingRevision) {
			historyGeneration++
			device.sample = null; removed += key.observationId; device.mappingRevision = revision
		}
		if (pose == null) {
			if (state!!.presence == "absent" || state.tracking_state in listOf("lost", "disconnected")) {
				device.absentAt = maxOf(device.absentAt, state.timestamp_ns)
				if (device.sample?.pose?.timestamp_ns?.let { it <= device.absentAt } != false) {
					if (device.sample != null) historyGeneration++
					device.sample = null; removed += key.observationId
				}
			}
			lifetimes.getValue(key.lifetimeId).lastAccepted = received.receivedAtNanos
            return // Present/battery heartbeat cannot repair or refresh a pose.
		}
		dirty += key
		device.sample = null
		if (pose.timestamp_ns <= device.absentAt) return
        lifetimes.getValue(key.lifetimeId).lastAccepted = received.receivedAtNanos
        if (suspended) return
		val age = pose.sent_at_ns - pose.timestamp_ns // Both U63, codec checked timestamp <= sent_at.
		if (age > received.receivedAtNanos) { inbox.count("BeforeLocalEpoch"); return }
		device.sample = Sample(pose, received.receivedAtNanos - age)
		inbox.count("PoseAccepted")
	}
}
