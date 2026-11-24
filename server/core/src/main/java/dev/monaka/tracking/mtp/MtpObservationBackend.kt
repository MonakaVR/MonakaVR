package dev.monaka.tracking.mtp

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v3.MtpPose as C3MtpPose
import dev.monaka.tracking.AssignmentSnapshotAwareObservationBackend
import dev.monaka.tracking.DeclaredTrackerCapabilities
import dev.monaka.tracking.LogicalTracker
import dev.monaka.tracking.ObservationBackend
import dev.monaka.tracking.ObservationStore
import dev.monaka.tracking.PoseObservation
import dev.monaka.tracking.TrackerBodyAssignments

/** Backend-owned admitted source context, independent of teacher extraction/assignment. */
internal data class MtpSourceContextSnapshot(
	val logicalTracker: LogicalTracker,
	val sourceEpoch: String,
	val calibrationEpoch: String,
	val mappingRevision: Long?,
	val coordinateSpace: CoordinateSpace?,
	val convention: String = requireNotNull(coordinateSpace).convention,
)

/** Server-thread incremental cache. Watermarks survive loss/pause; pose age never changes. */
class MtpObservationBackend(
	private val inbox: MtpInbox,
	private val assignments: TrackerBodyAssignments,
	private val expectedConvention: String,
	override val backendId: String = "mtp",
	override val profileId: String = "mtp",
) : ObservationBackend,
	AssignmentSnapshotAwareObservationBackend {
	/** Legacy callers supply their consumer-local alignment. Admission needs its convention. */
	constructor(
		inbox: MtpInbox,
		assignments: TrackerBodyAssignments,
		expectedSpace: CoordinateSpace,
		backendId: String = "mtp",
		profileId: String = "mtp",
	) : this(inbox, assignments, expectedSpace.convention, backendId, profileId)

	data class Sample(
		val pose: NormalMtpPose,
		val sampleTime: Long,
		val receivedAtNanos: Long = sampleTime,
		val legacyPose: MtpPose? = null,
		val c3Pose: C3MtpPose? = null,
		val acquisitionSequence: Long = pose.sequence,
	) {
		val legacySpace: CoordinateSpace? get() = legacyPose?.coordinate_space
		val legacyMappingRevision: Long? get() = legacyPose?.mapping_revision
	}

	private class Lifetime(
		val session: String,
		val clock: String,
		val peer: String,
		val orderedAcrossTypes: Boolean,
		val retired: MutableSet<String>,
	) {
		var quarantined = false
		var lastAccepted = -1L
	}

	private data class AcquisitionStream(val source: String, val device: String, val session: String)

	private class Device {
		var poseOrSharedSequence = -1L
		var stateSequence = -1L
		var legacyPoseSpace: CoordinateSpace? = null
		var legacyPoseMappingRevision: Long? = null
		val acquisitions = mutableMapOf<AcquisitionStream, Long>()
		var absentAt = -1L
		var sample: Sample? = null
		var context: MtpSourceContextSnapshot? = null
		var capabilities: DeclaredTrackerCapabilities? = null
		var capabilityConflict = false
	}
	private val lifetimes = linkedMapOf<String, Lifetime>()
	private val devices = linkedMapOf<LogicalTracker, Device>()
	// Display metadata is not a pose, capability or persistent identity. Keep it
	// through sample invalidation and same-identity sessions, with bounded memory.
	private val displayNames = linkedMapOf<LogicalTracker, String>()
	private val removed = linkedSetOf<String>()
	private val adapter = MtpPoseAdapter()
	private var assignmentGeneration = -1L
	var historyGeneration = 0L
		private set
	var suspended = false
		private set
	/** Optional detached evidence at each actual admission, before latest-sample coalescing. */
	private var admittedObserver: ((LogicalTracker, Sample) -> Unit)? = null
	fun observeAdmitted(observer: ((LogicalTracker, Sample) -> Unit)?) { admittedObserver = observer }

	init {
		require(expectedConvention == MTP_CONVENTION)
	}

	/** Static declaration survives absent/loss/pause; usability never follows from support. */
	data class CapabilitySnapshot(
		val tracker: LogicalTracker,
		val capabilities: DeclaredTrackerCapabilities?,
		val conflict: Boolean,
		val modality: String,
		val sampleTime: Long?,
		val positionValid: Boolean,
		val orientationValid: Boolean,
		val displayName: String? = null,
	) {
		fun freshAt(now: Long, timeoutNanos: Long): Boolean = !conflict && sampleTime?.let { it <= now && now - it < timeoutNanos } == true
	}
	fun capabilitiesSnapshot(): Map<LogicalTracker, CapabilitySnapshot> = java.util.Collections.unmodifiableMap(
		LinkedHashMap(
			devices.mapValues { (key, d) ->
				// Reuse the actual component gate, including confidence/tracking/Float checks.
				val adapted = d.sample?.let { sample ->
					runCatching {
						adapter.adapt(sample, dev.slimevr.tracking.trackers.TrackerPosition.HEAD)
					}.getOrNull()
				}
				CapabilitySnapshot(
					key,
					d.capabilities.takeUnless { d.capabilityConflict },
					d.capabilityConflict,
					d.sample?.pose?.modality ?: "none",
					d.sample?.sampleTime,
					adapted?.position != null,
					adapted?.rotation != null,
					displayNames[key],
				)
			},
		),
	)
	fun samples(): Map<LogicalTracker, Sample> = devices.mapNotNull { (k, d) -> d.sample?.let { k to it } }.toMap()
	/** Existing cache value only; diagnostic lookup never repolls or re-admits a sample. */
	fun sampleFor(key: LogicalTracker): Sample? = devices[key]?.sample
	internal fun declaredCapabilitiesFor(key: LogicalTracker): DeclaredTrackerCapabilities? =
		devices[key]?.takeUnless { it.capabilityConflict }?.capabilities
	internal fun sourceContexts(): Map<LogicalTracker, MtpSourceContextSnapshot> = java.util.Collections.unmodifiableMap(
		LinkedHashMap(
			devices.mapNotNull { (key, device) ->
				device.context?.let { key to it }
			}.toMap(),
		),
	)
	fun suspend(value: Boolean) {
		if (suspended != value) {
			invalidateSamples()
			suspended = value
		}
	}

	/**
	 * Resume-transition drain: advance replay and session watermarks for the
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
		for ((key, device) in devices) {
			device.sample = null
			device.context = null
			removed += key.observationId
		}
	}
	fun close() {
		admittedObserver = null
		invalidateSamples()
		inbox.clear()
		devices.clear()
		displayNames.clear()
		lifetimes.clear()
		assignmentGeneration = -1
	}
	override fun drainRemovedSources(): Set<String> = removed.toSet().also { removed.clear() }

	override fun poll(observedAtNanos: Long): List<PoseObservation> = poll(observedAtNanos, assignments.snapshot())

	override fun poll(observedAtNanos: Long, assignment: TrackerBodyAssignments.Snapshot): List<PoseObservation> {
		val dirty = linkedSetOf<LogicalTracker>()
		for (message in inbox.drain()) admit(message, dirty)
		if (assignment.generation != assignmentGeneration) {
			dirty += devices.keys
			removed += devices.keys.map { it.observationId }
			assignmentGeneration = assignment.generation
		}
		return dirty.mapNotNull { key ->
			// Replacement follows sequence admission, not the local timestamp ordering in ObservationStore.
			removed += key.observationId
			val sample = devices[key]?.sample ?: return@mapNotNull null
			val target = assignment.entries[key] ?: run {
				inbox.count("UnassignedPose")
				return@mapNotNull null
			}
			try {
				adapter.adapt(sample, target)
			} catch (_: IllegalArgumentException) {
				devices.getValue(key).sample = null
				devices.getValue(key).context = null
				inbox.count("FloatOverflow")
				null
			}
		}
	}

	private fun removeSource(source: String) {
		if (devices.any { (key, device) -> key.lifetimeId == source && device.sample != null }) historyGeneration++
		for (key in devices.keys.filter { it.lifetimeId == source }) {
			removed += key.observationId
			devices.getValue(key).sample = null
			devices.getValue(key).context = null
		}
	}
	private fun admit(received: MtpInbox.Received, dirty: MutableSet<LogicalTracker>) {
		val message = received.message
		val pose = message as? NormalMtpPose
		val key = LogicalTracker(message.source_id, message.tracker_id, message.publisher_id)
		// The wire identifies the publisher/sample, not transitive output origin.
		// Do not classify opaque IDs as proof of either external or output ancestry.
		val session = message.session_id
		val clock = message.clock_id
		val lifetime = lifetimes[key.lifetimeId]
		if (lifetime == null) {
			if (lifetimes.size >= 64) {
				inbox.count("SourceLimit")
				return
			}
			lifetimes[key.lifetimeId] = Lifetime(session, clock, received.peer, received.orderedAcrossTypes, linkedSetOf())
		} else {
			if (lifetime.quarantined || session in lifetime.retired) {
				inbox.count("RetiredSession")
				return
			}
			if (session == lifetime.session && received.peer != lifetime.peer) {
				inbox.count("PeerMismatch")
				return
			}
			if (session != lifetime.session) {
				if (received.receivedAtNanos - lifetime.lastAccepted < 500_000_000) {
					inbox.count("ActiveLease")
					return
				}
				removeSource(key.lifetimeId)
				// Do not evict retired UUIDs and accidentally allow replay. Saturation fails closed.
				if (lifetime.retired.size >= 256) {
					lifetime.quarantined = true
					inbox.count("SessionLimit")
					return
				}
				lifetime.retired += lifetime.session
				lifetimes[key.lifetimeId] = Lifetime(session, clock, received.peer, received.orderedAcrossTypes, lifetime.retired)
				devices.keys.filter { it.lifetimeId == key.lifetimeId }.forEach { devices.remove(it) }
				inbox.count("SessionChanged")
			} else if (received.orderedAcrossTypes != lifetime.orderedAcrossTypes) {
				inbox.count("WireVersionChangedInSession")
				return
			} else if (clock != lifetime.clock) {
				devices[key]?.let {
					it.sample = null
					it.context = null
				}
				removed += key.observationId
				inbox.count("ClockMismatch")
				return
			}
		}
		if (key !in devices && devices.size >= 1024) {
			inbox.count("TrackerLimit")
			return
		}
		val device = devices.getOrPut(key) { Device() }
		val sequence = message.sequence
		val sharedOrPose = received.orderedAcrossTypes || pose != null
		val previous = if (sharedOrPose) device.poseOrSharedSequence else device.stateSequence
		if (sequence <= previous) {
			inbox.count("DuplicateOrOldSequence")
			return
		}
		if (sharedOrPose) device.poseOrSharedSequence = sequence else device.stateSequence = sequence
		if (message.convention != expectedConvention) {
			if (device.sample != null) historyGeneration++
			device.sample = null
			device.context = null
			removed += key.observationId
			inbox.count("SpaceMismatch")
			return
		}
		val declaration = DeclaredTrackerCapabilities(message.capabilities)
		if (device.capabilityConflict || (device.capabilities != null && device.capabilities != declaration)) {
			device.capabilityConflict = true
			if (device.sample != null) historyGeneration++
			device.sample = null
			device.context = null
			removed += key.observationId
			inbox.count("CapabilityConflict")
			return
		}
		device.capabilities = declaration
		if (pose == null) {
			val state = message as NormalMtpTrackerState
			if (state.presence == "absent" || state.tracking_state in listOf("lost", "disconnected")) {
				device.absentAt = maxOf(device.absentAt, state.timestamp_ns)
				if (device.sample?.pose?.timestamp_ns?.let { it <= device.absentAt } != false) {
					if (device.sample != null) historyGeneration++
					device.sample = null
					device.context = null
					removed += key.observationId
				}
			}
			lifetimes.getValue(key.lifetimeId).lastAccepted = received.receivedAtNanos
			return // Present/battery heartbeat cannot repair or refresh a pose.
		}
		if (pose.timestamp_ns <= device.absentAt) return
		val prior = device.sample
		var reexpression = false
		if (received.orderedAcrossTypes) {
			val input = pose.input
			val stream = AcquisitionStream(input.source_id, input.device_id, input.session_id)
			val watermark = device.acquisitions[stream]
			if (watermark == null && device.acquisitions.size >= 256) {
				// Keep acquisition tombstones so a previously admitted input cannot
				// become fresh again after a remap or local pause.
				inbox.count("AcquisitionStreamLimit")
				return
			}
			if (watermark != null && input.sequence < watermark) {
				inbox.count("OldAcquisition")
				return
			}
			reexpression = watermark == input.sequence
			if (reexpression &&
				(prior == null || !prior.pose.input.sameAcquisition(input) || pose.timestamp_ns != prior.pose.timestamp_ns ||
					(pose.positionValid && !prior.pose.positionValid) || (pose.orientationValid && !prior.pose.orientationValid))
			) {
				inbox.count("ReexpressionWithoutAcquisition")
				return
			}
			device.acquisitions[stream] = input.sequence
		}
		dirty += key
		device.sample = null
		device.context = null
		lifetimes.getValue(key.lifetimeId).lastAccepted = received.receivedAtNanos
		if (suspended) return
		val age = pose.sent_at_ns - pose.timestamp_ns // Both U63, codec checked timestamp <= sent_at.
		if (!reexpression && age > received.receivedAtNanos) {
			inbox.count("BeforeLocalEpoch")
			return
		}
		// A legacy description change can reset transient history only when a newly
		// acquired pose replaces it. State alone cannot invalidate healthy geometry.
		val legacySpace = received.legacyPose?.coordinate_space
		val legacyMappingRevision = received.legacyPose?.mapping_revision
		if (device.legacyPoseSpace != null &&
			(device.legacyPoseSpace != legacySpace || device.legacyPoseMappingRevision != legacyMappingRevision)
		) {
			historyGeneration++
			if (device.legacyPoseSpace != legacySpace) inbox.count("SpaceDescriptionChanged")
		}
		device.legacyPoseSpace = legacySpace
		device.legacyPoseMappingRevision = legacyMappingRevision
		device.sample = if (reexpression) {
			// A new publication sequence can express new geometry on the same acquired
			// input. The admitted receipt and sample age belong to that acquisition.
			requireNotNull(prior).copy(pose = pose, c3Pose = received.c3Pose)
		} else {
			Sample(pose, received.receivedAtNanos - age, received.receivedAtNanos, received.legacyPose, received.c3Pose)
		}
		// Descriptive metadata follows admitted public messages, independently of
		// component usability. Existing observation gates continue to own tracking.
		displayNames.remove(key)
		displayNames[key] = pose.input.device_id
		if (displayNames.size > 1024) displayNames.remove(displayNames.keys.first())
		device.context = MtpSourceContextSnapshot(
			key,
			"${pose.session_id}:${pose.clock_id}",
			pose.input.session_id,
			legacyMappingRevision,
			legacySpace,
			pose.convention,
		)
		inbox.count("WireOriginUnknown")
		inbox.count("PoseAccepted")
		admittedObserver?.let { observer -> runCatching { observer(key, requireNotNull(device.sample)) } }
	}
}
