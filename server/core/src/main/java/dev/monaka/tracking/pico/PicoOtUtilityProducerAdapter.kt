package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Normalized tracker reading expected from the concrete PICO SDK/Utility bridge.
 *
 * The bridge is responsible only for translating PICO-specific API objects/status
 * codes into these backend-neutral values. It does not build transport frames or
 * manage cadence, sessions, sequence numbers, battery caching, or UDP transport.
 */
data class PicoOtUtilityTrackerPose(
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

/** Complete authoritative tracker set from one concrete PICO SDK/Utility read. */
data class PicoOtUtilityPoseSnapshot(
	val trackingSpaceId: String,
	val trackers: List<PicoOtUtilityTrackerPose>,
) {
	init {
		require(trackingSpaceId.isNotBlank()) { "trackingSpaceId must not be blank" }
		val ids = trackers.map { it.trackerId }
		require(ids.size == ids.toSet().size) { "PICO OT utility snapshot contains duplicate tracker ids" }
	}
}

/**
 * Concrete PICO integration boundary.
 *
 * A platform-specific module implements this interface using the actual PICO SDK,
 * Unity plugin, Android service, IPC client, or another utility layer. Stable tracker
 * ids and tracking-space identity must be preserved by that implementation.
 *
 * [coordinateConvention] describes the pose values returned by [readPoseSnapshot].
 * It is stable for one bridge instance and is copied into the producer contract so
 * Monaka can validate and normalize coordinates at the receiver boundary.
 */
interface PicoOtUtilityBridge {
	val coordinateConvention: PicoOtCoordinateConvention

	fun readPoseSnapshot(): PicoOtUtilityPoseSnapshot

	fun readBatteryPercent(trackerIds: Set<String>): Map<String, Int>
}

/**
 * Adapts the concrete PICO utility boundary into the producer runtime's split pose
 * and battery source contracts.
 */
class PicoOtUtilityProducerAdapter(
	private val bridge: PicoOtUtilityBridge,
) : PicoOtProducerPoseSource, PicoOtProducerBatterySource {
	val coordinateConvention: PicoOtCoordinateConvention
		get() = bridge.coordinateConvention

	override fun snapshot(): PicoOtProducerPoseSnapshot {
		val snapshot = bridge.readPoseSnapshot()
		return PicoOtProducerPoseSnapshot(
			trackingSpaceId = snapshot.trackingSpaceId,
			trackers = snapshot.trackers.map { tracker ->
				PicoOtProducerPoseSample(
					trackerId = tracker.trackerId,
					position = tracker.position,
					rotation = tracker.rotation,
					positionState = tracker.positionState,
					rotationState = tracker.rotationState,
				)
			},
			coordinateConvention = bridge.coordinateConvention,
		)
	}

	override fun pollBatteryPercent(trackerIds: Set<String>): Map<String, Int> =
		bridge.readBatteryPercent(trackerIds)

	/**
	 * Creates a producer session guaranteed to advertise the same coordinate basis as
	 * this adapter. The initial tracking-space id still comes from the host because a
	 * session may be created before the first SDK pose read.
	 */
	fun createSession(
		sessionId: String,
		initialTrackingSpaceId: String,
	): PicoOtProducerSession = PicoOtProducerSession(
		sessionId = sessionId,
		initialTrackingSpaceId = initialTrackingSpaceId,
		coordinateConvention = coordinateConvention,
	)
}
