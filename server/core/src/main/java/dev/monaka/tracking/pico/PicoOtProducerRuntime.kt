package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Pose-only sample obtained from the concrete PICO tracking API.
 *
 * Battery metadata is intentionally absent here. [PicoOtProducerRuntime] polls it
 * independently through [PicoOtProducerBatterySource] so a low-rate battery query
 * cannot throttle the high-rate pose path.
 */
data class PicoOtProducerPoseSample(
	val trackerId: String,
	val position: Vector3? = null,
	val rotation: Quaternion? = null,
	val positionState: PicoOtComponentTrackingState = if (position != null) {
		PicoOtComponentTrackingState.TRACKED
	} else {
		PicoOtComponentTrackingState.UNAVAILABLE
	},
	val rotationState: PicoOtComponentTrackingState = if (rotation != null) {
		PicoOtComponentTrackingState.TRACKED
	} else {
		PicoOtComponentTrackingState.UNAVAILABLE
	},
) {
	init {
		require(trackerId.isNotBlank()) { "trackerId must not be blank" }
		require(positionState != PicoOtComponentTrackingState.TRACKED || position != null) {
			"TRACKED position state requires a position value"
		}
		require(positionState != PicoOtComponentTrackingState.DEGRADED || position != null) {
			"DEGRADED position state requires a position value"
		}
		require(rotationState != PicoOtComponentTrackingState.TRACKED || rotation != null) {
			"TRACKED rotation state requires a rotation value"
		}
		require(rotationState != PicoOtComponentTrackingState.DEGRADED || rotation != null) {
			"DEGRADED rotation state requires a rotation value"
		}
	}
}

/**
 * Complete authoritative pose set returned by one producer-side SDK read.
 *
 * [coordinateConvention] describes the values exactly as emitted by the concrete
 * SDK/utility adapter. It must remain consistent with the producer session so the
 * transport never advertises a coordinate basis different from the payload values.
 */
data class PicoOtProducerPoseSnapshot(
	val trackingSpaceId: String,
	val trackers: List<PicoOtProducerPoseSample>,
	val coordinateConvention: PicoOtCoordinateConvention =
		PicoOtCoordinateConvention.MONAKA_RH_Y_UP_NEG_Z_FORWARD_METERS,
) {
	init {
		require(trackingSpaceId.isNotBlank()) { "trackingSpaceId must not be blank" }
		val ids = trackers.map { it.trackerId }
		require(ids.size == ids.toSet().size) { "PICO OT producer pose snapshot contains duplicate tracker ids" }
	}
}

fun interface PicoOtProducerPoseSource {
	fun snapshot(): PicoOtProducerPoseSnapshot
}

/**
 * Low-rate battery source. Implementations may return a sparse map when a battery
 * value is temporarily unavailable. Returned ids must belong to [trackerIds].
 */
fun interface PicoOtProducerBatterySource {
	fun pollBatteryPercent(trackerIds: Set<String>): Map<String, Int>
}

/** Transport-neutral output boundary used by the producer runtime. */
fun interface PicoOtFrameSender {
	fun send(frame: PicoOtTransportFrame): Int
}

/**
 * Deterministic producer orchestration core.
 *
 * Call [tick] from the HMD/utility host with a monotonic timestamp. The runtime reads
 * pose at the configured pose cadence, polls battery independently at its lower
 * cadence, maintains sparse battery state only while a tracker remains present,
 * builds a session/sequence frame, and sends it through [sender].
 *
 * Cadence marks are committed only after the corresponding operation succeeds. A
 * failed send therefore leaves pose due for retry. Sequence gaps are still allowed
 * because a frame is allocated before transport send, matching normal UDP packet loss.
 */
class PicoOtProducerRuntime(
	private val session: PicoOtProducerSession,
	private val cadence: PicoOtProducerCadence,
	private val poseSource: PicoOtProducerPoseSource,
	private val batterySource: PicoOtProducerBatterySource,
	private val sender: PicoOtFrameSender,
) {
	private val batteryByTrackerId = linkedMapOf<String, Int>()

	var poseSnapshotsRead: Long = 0L
		private set

	var batteryPolls: Long = 0L
		private set

	var framesSent: Long = 0L
		private set

	var lastFrame: PicoOtTransportFrame? = null
		private set

	var lastBytesSent: Int? = null
		private set

	fun tick(nowNanos: Long): Boolean {
		require(nowNanos >= 0L) { "nowNanos must be non-negative" }
		if (!cadence.isPoseDue(nowNanos)) return false

		val poseSnapshot = poseSource.snapshot()
		poseSnapshotsRead++
		require(poseSnapshot.coordinateConvention == session.coordinateConvention) {
			"PICO OT producer pose convention ${poseSnapshot.coordinateConvention} does not match session convention ${session.coordinateConvention}"
		}

		val presentIds = poseSnapshot.trackers.mapTo(linkedSetOf()) { it.trackerId }
		batteryByTrackerId.keys.retainAll(presentIds)

		if (cadence.isBatteryPollDue(nowNanos)) {
			val polled = batterySource.pollBatteryPercent(presentIds)
			validateBatteryResult(polled, presentIds)
			batteryByTrackerId.putAll(polled)
			cadence.markBatteryPolled(nowNanos)
			batteryPolls++
		}

		session.updateTrackingSpace(poseSnapshot.trackingSpaceId)
		val frame = session.buildFrame(
			poseSnapshot.trackers.map { tracker ->
				PicoOtTrackerSample(
					trackerId = tracker.trackerId,
					position = tracker.position,
					rotation = tracker.rotation,
					positionState = tracker.positionState,
					rotationState = tracker.rotationState,
					batteryPercent = batteryByTrackerId[tracker.trackerId],
				)
			},
		)

		val bytesSent = sender.send(frame)
		require(bytesSent >= 0) { "PICO OT frame sender returned a negative byte count" }
		cadence.markPoseSent(nowNanos)

		lastFrame = frame
		lastBytesSent = bytesSent
		framesSent++
		return true
	}

	fun batterySnapshot(): Map<String, Int> = batteryByTrackerId.toMap()

	private fun validateBatteryResult(
		battery: Map<String, Int>,
		presentIds: Set<String>,
	) {
		for ((trackerId, percent) in battery) {
			require(trackerId.isNotBlank()) { "battery tracker id must not be blank" }
			require(trackerId in presentIds) {
				"battery source returned unknown tracker id '$trackerId'"
			}
			require(percent in 0..100) {
				"batteryPercent for '$trackerId' must be between 0 and 100"
			}
		}
	}
}
