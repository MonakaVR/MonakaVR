package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * One pose-bearing tracker entry exposed by the existing PicoMotionTrackerBridge PC receiver.
 *
 * [senderSessionId] and [lastPoseSequence] are diagnostic/lifecycle metadata from the
 * bridge transport. Session ids are treated as opaque 64-bit values; sequence is the
 * bridge's unsigned 32-bit packet sequence widened to [Long].
 */
data class PicoMotionTrackerBridgeReceiverTracker(
	val serial: String,
	val senderSessionId: Long,
	val lastPoseSequence: Long,
	val connectedSnapshot: Boolean,
	val positionMeters: Vector3? = null,
	val orientation: Quaternion? = null,
	val positionValid: Boolean,
	val orientationSamplePresent: Boolean,
	val lastPoseReceivePcMonotonicNanos: Long,
	val mappedPosePcMonotonicNanos: Long? = null,
	val referenceFrame: PicoMotionTrackerBridgeReferenceFrame = PicoMotionTrackerBridgeReferenceFrame.PICO_OUTPUT_A,
) {
	init {
		require(serial.isNotBlank()) { "receiver tracker serial must not be blank" }
		require(senderSessionId != 0L) { "receiver tracker senderSessionId must not be zero" }
		require(lastPoseSequence in 0L..0xffff_ffffL) {
			"receiver tracker sequence must fit unsigned 32-bit range"
		}
		require(lastPoseReceivePcMonotonicNanos >= 0L) {
			"receiver tracker receive timestamp must be non-negative"
		}
		require(mappedPosePcMonotonicNanos == null || mappedPosePcMonotonicNanos >= 0L) {
			"receiver tracker mapped timestamp must be non-negative"
		}
		require(!positionValid || positionMeters != null) {
			"receiver positionValid requires a position sample"
		}
		require(!orientationSamplePresent || orientation != null) {
			"receiver orientationSamplePresent requires an orientation sample"
		}
	}
}

/**
 * Atomic view of one PicoMotionTrackerBridge Receiver state after Pump().
 *
 * The native Receiver clears tracker state when a new HMD sender session becomes active.
 * This contract preserves that property and rejects mixed-session snapshots before they
 * can enter Monaka.
 */
data class PicoMotionTrackerBridgeReceiverSnapshot(
	val activeSenderSessionId: Long? = null,
	val trackers: List<PicoMotionTrackerBridgeReceiverTracker> = emptyList(),
) {
	init {
		require(activeSenderSessionId == null || activeSenderSessionId != 0L) {
			"activeSenderSessionId must be null or non-zero"
		}
		if (activeSenderSessionId == null) {
			require(trackers.isEmpty()) {
				"receiver snapshot without an active sender cannot contain tracker poses"
			}
		} else {
			require(trackers.all { it.senderSessionId == activeSenderSessionId }) {
				"receiver snapshot contains tracker state from another sender session"
			}
		}
		val serials = trackers.map { it.serial }
		require(serials.size == serials.toSet().size) {
			"receiver snapshot contains duplicate tracker serials"
		}
	}
}

/**
 * Narrow native/process boundary for the existing PicoMotionTrackerBridge Receiver.
 *
 * Implementations own the real C++ receiver or another lossless wrapper around it.
 * Monaka does not decode the HMD binary UDP protocol itself. [pump] should surface a
 * receiver failure by throwing; [readSnapshot] returns one internally consistent view.
 */
interface PicoMotionTrackerBridgeReceiverClient {
	fun pump()
	fun readSnapshot(): PicoMotionTrackerBridgeReceiverSnapshot
}

/**
 * Adapts the existing bridge Receiver into the Monaka state-provider contract.
 *
 * Stale tracker entries are intentionally retained here. The C++ Receiver already keeps
 * the latest pose per serial and Monaka's component freshness policy ages it using the
 * immutable PC receive timestamp. Sender-session changes, on the other hand, are
 * authoritative topology changes and arrive as a cleared/new receiver snapshot.
 */
class PicoMotionTrackerBridgeReceiverStateProvider(
	private val receiver: PicoMotionTrackerBridgeReceiverClient,
) : PicoMotionTrackerBridgeStateProvider {
	override fun snapshot(): List<PicoMotionTrackerBridgeState> {
		receiver.pump()
		val snapshot = receiver.readSnapshot()
		if (snapshot.activeSenderSessionId == null) return emptyList()

		return snapshot.trackers.map { tracker ->
			PicoMotionTrackerBridgeState(
				serial = tracker.serial,
				connectedSnapshot = tracker.connectedSnapshot,
				positionMeters = tracker.positionMeters,
				orientation = tracker.orientation,
				positionValid = tracker.positionValid,
				orientationSamplePresent = tracker.orientationSamplePresent,
				lastPoseReceivePcMonotonicNanos = tracker.lastPoseReceivePcMonotonicNanos,
				mappedPosePcMonotonicNanos = tracker.mappedPosePcMonotonicNanos,
				referenceFrame = tracker.referenceFrame,
			)
		}
	}
}
