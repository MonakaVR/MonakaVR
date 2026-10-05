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
	val receivedAtSystemNanos: Long,
	val sourceEpoch: String,
	val ingressIdentity: RawSourceIdentity,
) {
	// Receipt provenance alone cannot prove the HMD world frame or a paired orientation sample.
	val rawPoseInputEligible: Boolean get() = false
	val rawPoseInputRejectionReasons: Set<String> get() = setOf(
		"hmd_space_unverified", "hmd_frame_epoch_unavailable", "hmd_pose_pairing_unavailable",
	)
}

data class PositionComponentPresence(val x: Boolean, val y: Boolean, val z: Boolean) {
	val complete: Boolean get() = x && y && z
}

enum class HmdPoseMessagePairingStatus { COMPLETE, INCOMPLETE_POSITION, INVALID_POSITION, INVALID_ORIENTATION }

/** Same decoded-message values, not acquisition-time, transport-session or coordinate-frame proof. */
@ConsistentCopyVisibility
data class HmdAcceptedPoseMessageSample internal constructor(
	val position: Vector3,
	val positionPresence: PositionComponentPresence,
	val orientation: Quaternion,
	val sequence: Long,
	val receivedAtSystemNanos: Long,
	val sourceEpoch: String,
	val ingressIdentity: RawSourceIdentity,
	val dataSourceValue: Int,
	val dataSourcePresent: Boolean,
	val modality: TrackingModality,
) {
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
	val rawPoseInputEligible: Boolean get() = false
	val rawPoseInputRejectionReasons: Set<String> get() = structuralRejectionReasons + setOf(
		"hmd_space_unverified", "hmd_frame_epoch_unavailable", "hmd_session_epoch_unavailable",
	)
}

/**
 * Registered only by SteamVRBridge's remote-tracker creation boundary. Mutations are server-thread
 * confined; readers get one volatile immutable value+metadata snapshot, never mutable Tracker.position.
 * Source lifetime is object lifetime only: transport queues do not carry a connection/session ID.
 */
internal class TrustedRawHmdPositionSource(
	private val bridgeIdentity: String,
	private val receiptClock: () -> Long = System::nanoTime,
) {
	private var registeredTracker: Tracker? = null
	private var epoch: String? = null
	private var identity: RawSourceIdentity? = null
	private var sequence = 0L
	private data class AcceptedMessage(val position: HmdAcceptedPositionSample, val pose: HmdAcceptedPoseMessageSample)
	@Volatile private var latest: AcceptedMessage? = null

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
		latest = null
	}

	/** Invoke only immediately after the existing hasX position write accepts a payload. */
	@VRServerThread
	fun positionMessageAccepted(tracker: Tracker, position: Vector3, message: Position, modality: TrackingModality) {
		if (registeredTracker !== tracker) return
		val accepted = HmdAcceptedPositionSample(Vector3(position.x, position.y, position.z), ++sequence,
			receiptClock(), requireNotNull(epoch), requireNotNull(identity))
		// Copy Q directly from this message, never Tracker rotation or its independent sequence.
		val pose = HmdAcceptedPoseMessageSample(
			accepted.position, PositionComponentPresence(message.hasX(), message.hasY(), message.hasZ()),
			Quaternion(message.qw, message.qx, message.qy, message.qz),
			accepted.sequence, accepted.receivedAtSystemNanos, accepted.sourceEpoch, accepted.ingressIdentity,
			message.dataSourceValue, message.hasDataSource(), modality,
		)
		latest = AcceptedMessage(accepted, pose)
	}

	fun snapshot(): HmdAcceptedPositionSample? = latest?.position
	fun poseMessageSnapshot(): HmdAcceptedPoseMessageSample? = latest?.pose
}
