package dev.monaka.tracking.desktop

import dev.monaka.tracking.FeedbackExclusion
import dev.monaka.tracking.RawSourceIdentity
import dev.monaka.tracking.RawSourceKind
import dev.monaka.tracking.TrackingModality
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.util.ann.VRServerThread
import io.github.axisangles.ktmath.Vector3
import io.github.axisangles.ktmath.Quaternion
import java.util.UUID

/** Historical acceptance record, not a usable pose, observation or physical acquisition timestamp. */
@ConsistentCopyVisibility
data class HmdAcceptedPositionSample internal constructor(
	val position: Vector3,
	val sequence: Long,
	/** Preserved host monotonic decoded-message ingress time, not device acquisition time. */
	val receivedAtSystemNanos: Long,
	val sourceEpoch: String,
	val ingressIdentity: RawSourceIdentity,
	val transportSessionEpoch: String? = null,
) {
	init { require(transportSessionEpoch == null || transportSessionEpoch.isNotBlank()) }
	// Context-free metadata gaps only. Eligibility belongs to currentRawHmdPoseAdmission().
	val rawPoseMetadataLimitations: Set<String> get() = setOf(
		"hmd_space_unverified", "hmd_frame_epoch_unavailable", "hmd_pose_pairing_unavailable",
	)
}

data class PositionComponentPresence(val x: Boolean, val y: Boolean, val z: Boolean) {
	val complete: Boolean get() = x && y && z
}

enum class HmdPoseMessagePairingStatus { COMPLETE, INCOMPLETE_POSITION, INVALID_POSITION, INVALID_ORIENTATION }

/** Same decoded-message values; optional transport lineage does not prove time, frame or freshness. */
@ConsistentCopyVisibility
data class HmdAcceptedPoseMessageSample internal constructor(
	val position: Vector3,
	val positionPresence: PositionComponentPresence,
	val orientation: Quaternion,
	val sequence: Long,
	/** Preserved host monotonic decoded-message ingress time, not device acquisition time. */
	val receivedAtSystemNanos: Long,
	val sourceEpoch: String,
	val ingressIdentity: RawSourceIdentity,
	val dataSourceValue: Int,
	val dataSourcePresent: Boolean,
	val modality: TrackingModality,
	val transportSessionEpoch: String? = null,
	/** Exact provider snapshot, absent on generic OpenVR. Never synthesized by this receiver. */
	val providerEvidence: RawHmdProviderPoseEvidence? = null,
) {
	init { require(transportSessionEpoch == null || transportSessionEpoch.isNotBlank()) }
	// Match Phase 2A's numeric validity contract; retain decoded values without normalization.
	val structuralRejectionReasons: Set<String> get() = buildSet {
		if (!positionPresence.complete) add("hmd_position_components_incomplete")
		if (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) add("hmd_position_nonfinite")
		if (!orientation.w.isFinite() || !orientation.x.isFinite() || !orientation.y.isFinite() ||
			!orientation.z.isFinite() || !orientation.lenSq().isFinite() || orientation.lenSq() <= 1e-10f
		) add("hmd_orientation_invalid")
	}
	val pairingStatus: HmdPoseMessagePairingStatus get() = when {
		"hmd_position_components_incomplete" in structuralRejectionReasons -> HmdPoseMessagePairingStatus.INCOMPLETE_POSITION
		"hmd_position_nonfinite" in structuralRejectionReasons -> HmdPoseMessagePairingStatus.INVALID_POSITION
		"hmd_orientation_invalid" in structuralRejectionReasons -> HmdPoseMessagePairingStatus.INVALID_ORIENTATION
		else -> HmdPoseMessagePairingStatus.COMPLETE
	}
	// This record carries no frame evidence; external pose-bound admission evidence is separate.
	val rawPoseMetadataLimitations: Set<String> get() = structuralRejectionReasons + buildSet {
		add("hmd_space_unverified")
		add("hmd_frame_epoch_unavailable")
		if (transportSessionEpoch == null) add("hmd_session_epoch_unavailable")
	}
}

/**
 * Registered only by SteamVRBridge's remote-tracker creation boundary. Mutations are server-thread
 * confined; readers get one volatile immutable value+metadata snapshot, never mutable Tracker.position.
 * Object source lifetime and optional enqueue-time transport lifetime remain separate identities.
 * Historical acceptance and current-session candidates use separate immutable holders.
 */
internal class TrustedRawHmdPositionSource(
	private val bridgeIdentity: String,
) {
	// Generic ingress proves receipt/pairing/session, never an authoritative pose-level HMD frame.
	val rawHmdPoseInputCapability: RawHmdPoseInputCapability = RawHmdPoseInputCapability.Unavailable(
		setOf(RawHmdPoseInputRejectionReason.FRAME_REFERENCE_UNAVAILABLE,
			RawHmdPoseInputRejectionReason.PROVIDER_SESSION_UNAVAILABLE,
			RawHmdPoseInputRejectionReason.OBSERVATION_ID_UNAVAILABLE,
			RawHmdPoseInputRejectionReason.RAW_SPACE_GENERATION_UNAVAILABLE),
	)
	private var registeredTracker: Tracker? = null
	private var epoch: String? = null
	private var identity: RawSourceIdentity? = null
	private var sequence = 0L
	private data class AcceptedMessage(val position: HmdAcceptedPositionSample, val pose: HmdAcceptedPoseMessageSample)
	@Volatile private var historicalLatest: AcceptedMessage? = null
	@Volatile private var currentSessionLatest: AcceptedMessage? = null
	private val providerObservations = HmdProviderObservationState()
	// Independent of historical/current normal ingress records. Loss never falls back to history.
	@Volatile private var trustedCandidate: HmdAcceptedPoseMessageSample? = null
	@Volatile private var providerSession: RawHmdProviderSession? = null
	private var providerTransportEpoch: String? = null
	private var providerPolicy: RawHmdPoseFreshnessPolicy? = null
	private var usable = true
	@Volatile private var trustedTransportEpoch: String? = null

	/** Internal reviewed backend extension only; production OpenVR never establishes this context. */
	@VRServerThread
	fun establishProviderSession(context: RawHmdProviderSession, policy: RawHmdPoseFreshnessPolicy): Boolean {
		if (providerTransportEpoch != trustedTransportEpoch) retireProviderSession()
		if (!providerObservations.establish(context)) {
			providerSession = providerObservations.session; trustedCandidate = null
			return false
		}
		providerObservations.invalidate()
		providerSession = context; providerPolicy = policy; trustedCandidate = null
		providerTransportEpoch = trustedTransportEpoch
		return true
	}

	@VRServerThread
	fun invalidateCurrentCandidate(tracker: Tracker? = null) {
		if (tracker != null && tracker !== registeredTracker) return
		providerObservations.invalidate(); trustedCandidate = null
	}

	/** Transport-thread boundary only changes volatile eligibility; observation state stays server-thread. */
	fun onTransportSessionChanged(activeEpoch: String?) { trustedTransportEpoch = activeEpoch; trustedCandidate = null }

	@VRServerThread
	fun retireProviderSession() {
		providerObservations.retire(); providerSession = null; trustedCandidate = null
	}

	@VRServerThread
	fun disconnected() { usable = false; retireProviderSession() }

	@VRServerThread
	fun trackingStatusChanged(tracker: Tracker) {
		if (tracker !== registeredTracker) return
		usable = tracker.status.sendData
		if (!usable) invalidateCurrentCandidate(tracker)
	}

	fun currentTrustedPoseSnapshot(activeEpoch: String): HmdAcceptedPoseMessageSample? =
		trustedCandidate?.takeIf { activeEpoch == trustedTransportEpoch && it.transportSessionEpoch == activeEpoch }
	fun currentProviderSession(): RawHmdProviderSession? = providerSession?.takeIf { providerTransportEpoch == trustedTransportEpoch }

	@VRServerThread
	fun registerFromSteamVrIngress(tracker: Tracker) {
		if (tracker.device?.origin != DeviceOrigin.STEAMVR || tracker.trackerNum != 0 ||
			!tracker.isHmd || tracker.isInternal || !tracker.hasPosition || !tracker.hasRotation ||
			tracker.monakaOutputPose != null || FeedbackExclusion.isOutput(tracker.name)) return
		if (registeredTracker === tracker) return
		registeredTracker = tracker
		epoch = UUID.randomUUID().toString()
		identity = RawSourceIdentity("steamvr:$bridgeIdentity:${tracker.name}", RawSourceKind.RAW_HMD,
			isComputed = tracker.isComputed, isHmd = true)
		sequence = 0
		historicalLatest = null
		currentSessionLatest = null
		retireProviderSession()
		usable = tracker.status.sendData
	}

	/** Invoke only immediately after the existing hasX position write accepts a payload. */
	@VRServerThread
	fun positionMessageAccepted(
		tracker: Tracker, position: Vector3, message: Position, modality: TrackingModality,
		transportSessionEpoch: String?, isCurrentTransportSession: Boolean,
		receivedAtSystemNanos: Long?,
		providerEvidence: RawHmdProviderPoseEvidence? = null,
	) {
		if (registeredTracker !== tracker) return
		// Missing ingress metadata cannot be repaired with processing time or an older candidate.
		if (receivedAtSystemNanos == null) {
			if (isCurrentTransportSession) { currentSessionLatest = null; invalidateCurrentCandidate(tracker) }
			return
		}
		// Handles guarantee nonblank epochs. Unexpected invalid metadata must not throw into tracking.
		if (transportSessionEpoch != null && transportSessionEpoch.isBlank()) {
			currentSessionLatest = null
			invalidateCurrentCandidate(tracker)
			return
		}
		val accepted = HmdAcceptedPositionSample(Vector3(position.x, position.y, position.z), ++sequence,
			receivedAtSystemNanos, requireNotNull(epoch), requireNotNull(identity), transportSessionEpoch)
		// Copy Q directly from this message, never Tracker rotation or its independent sequence.
		val pose = HmdAcceptedPoseMessageSample(
			accepted.position, PositionComponentPresence(message.hasX(), message.hasY(), message.hasZ()),
			Quaternion(message.qw, message.qx, message.qy, message.qz),
			accepted.sequence, accepted.receivedAtSystemNanos, accepted.sourceEpoch, accepted.ingressIdentity,
			message.dataSourceValue, message.hasDataSource(), modality, transportSessionEpoch, providerEvidence,
		)
		val acceptedMessage = AcceptedMessage(accepted, pose)
		historicalLatest = acceptedMessage
		if (isCurrentTransportSession && transportSessionEpoch != null) {
			currentSessionLatest = acceptedMessage
			if (!usable || providerEvidence == null || providerPolicy == null || transportSessionEpoch != trustedTransportEpoch ||
				providerTransportEpoch != transportSessionEpoch) {
				invalidateCurrentCandidate(tracker)
			} else {
				providerObservations.observe(pose, requireNotNull(providerPolicy))
				trustedCandidate = providerObservations.candidate
				providerSession = providerObservations.session
			}
		}
	}

	fun snapshot(): HmdAcceptedPositionSample? = historicalLatest?.position
	fun poseMessageSnapshot(): HmdAcceptedPoseMessageSample? = historicalLatest?.pose
	fun currentPositionSnapshot(activeEpoch: String): HmdAcceptedPositionSample? =
		currentSessionLatest?.takeIf { it.position.transportSessionEpoch == activeEpoch }?.position
	fun currentPoseMessageSnapshot(activeEpoch: String): HmdAcceptedPoseMessageSample? =
		currentSessionLatest?.takeIf { it.pose.transportSessionEpoch == activeEpoch }?.pose
}
