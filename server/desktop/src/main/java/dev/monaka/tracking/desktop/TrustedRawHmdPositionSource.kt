package dev.monaka.tracking.desktop

import dev.monaka.tracking.FeedbackExclusion
import dev.monaka.tracking.RawSourceIdentity
import dev.monaka.tracking.RawSourceKind
import dev.slimevr.tracking.trackers.DeviceOrigin
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.util.ann.VRServerThread
import io.github.axisangles.ktmath.Vector3
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
	@Volatile private var latest: HmdAcceptedPositionSample? = null

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
	fun positionAccepted(tracker: Tracker, position: Vector3) {
		if (registeredTracker !== tracker) return
		latest = HmdAcceptedPositionSample(Vector3(position.x, position.y, position.z), ++sequence,
			receiptClock(), requireNotNull(epoch), requireNotNull(identity))
	}

	fun snapshot(): HmdAcceptedPositionSample? = latest
}
