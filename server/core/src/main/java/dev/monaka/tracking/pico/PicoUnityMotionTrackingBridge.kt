package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.roundToInt

const val PICO_UNITY_RESULT_SUCCESS: Int = 0

/**
 * Result of the current PICO Unity Integration SDK GetMotionTrackerLocation call.
 *
 * The current Unity API exposes one whole-pose validity flag rather than independent
 * position/rotation confidence. The adapter therefore treats a valid pose as TRACKED
 * for both components, and an invalid/failed pose as LOST for both components. It
 * deliberately does not invent DEGRADED or component-specific validity that the SDK
 * did not report.
 */
data class PicoUnityMotionTrackerLocationResult(
	val resultCode: Int,
	val isValidPose: Boolean,
	val position: Vector3? = null,
	val rotation: Quaternion? = null,
) {
	init {
		if (resultCode == PICO_UNITY_RESULT_SUCCESS && isValidPose) {
			require(position != null) { "A valid PICO Unity tracker pose requires position" }
			require(rotation != null) { "A valid PICO Unity tracker pose requires rotation" }
		}
	}
}

/** Result of PXR_MotionTracking.GetMotionTrackerBattery. */
data class PicoUnityMotionTrackerBatteryResult(
	val resultCode: Int,
	val batteryLevel: Float? = null,
) {
	init {
		require(batteryLevel == null || batteryLevel.isFinite()) {
			"PICO Unity tracker battery level must be finite"
		}
	}
}

/**
 * Thin host boundary matching the PICO Unity Integration SDK object-tracking path.
 *
 * A Unity-side implementation is expected to source connected tracker ids from the
 * current request/connection callbacks, call GetMotionTrackerLocation(id, ...), and
 * call GetMotionTrackerBattery(id, ...). The SDK's current location object is already
 * converted into Unity Vector3/Quaternion values before crossing this boundary.
 *
 * [trackingSpaceId] remains host-managed because the current object-tracking pose API
 * does not expose a tracking-origin generation/recenter id. The host must change it
 * when it can positively detect a discontinuous tracking-space reset.
 */
interface PicoUnityMotionTrackingClient {
	fun connectedTrackerIds(): List<Long>

	fun trackingSpaceId(): String

	fun getMotionTrackerLocation(trackerId: Long): PicoUnityMotionTrackerLocationResult

	fun getMotionTrackerBattery(trackerId: Long): PicoUnityMotionTrackerBatteryResult
}

/**
 * Concrete semantics adapter for the PICO Unity Integration SDK object-tracking API.
 *
 * PICO's Unity-facing pose values are represented in Unity's left-handed +Z-forward
 * basis. The transport advertises that basis explicitly so the Monaka receiver can
 * perform the already-tested Unity -> canonical conversion exactly once.
 *
 * Connected tracker ids define the authoritative source set. An id that is still
 * connected but temporarily has an invalid optical pose remains present as LOST;
 * a disconnected id disappears from the snapshot completely.
 */
class PicoUnityMotionTrackingBridge(
	private val client: PicoUnityMotionTrackingClient,
) : PicoOtUtilityBridge {
	override val coordinateConvention: PicoOtCoordinateConvention =
		PicoOtCoordinateConvention.UNITY_LH_Y_UP_POS_Z_FORWARD_METERS

	override fun readPoseSnapshot(): PicoOtUtilityPoseSnapshot {
		val connectedIds = client.connectedTrackerIds()
		require(connectedIds.size == connectedIds.toSet().size) {
			"PICO Unity client returned duplicate tracker ids"
		}

		return PicoOtUtilityPoseSnapshot(
			trackingSpaceId = client.trackingSpaceId(),
			trackers = connectedIds.map { trackerId ->
				require(trackerId >= 0L) { "PICO Unity tracker id must be non-negative" }
				val location = client.getMotionTrackerLocation(trackerId)
				val tracked = location.resultCode == PICO_UNITY_RESULT_SUCCESS && location.isValidPose

				PicoOtUtilityTrackerPose(
					trackerId = trackerId.toString(),
					position = location.position,
					rotation = location.rotation,
					positionState = if (tracked) {
						PicoOtComponentTrackingState.TRACKED
					} else {
						PicoOtComponentTrackingState.LOST
					},
					rotationState = if (tracked) {
						PicoOtComponentTrackingState.TRACKED
					} else {
						PicoOtComponentTrackingState.LOST
					},
				)
			},
		)
	}

	override fun readBatteryPercent(trackerIds: Set<String>): Map<String, Int> {
		val battery = linkedMapOf<String, Int>()
		for (trackerId in trackerIds) {
			val numericId = trackerId.toLongOrNull()
				?: throw IllegalArgumentException("PICO Unity tracker id '$trackerId' is not numeric")
			require(numericId >= 0L) { "PICO Unity tracker id must be non-negative" }

			val result = client.getMotionTrackerBattery(numericId)
			if (result.resultCode != PICO_UNITY_RESULT_SUCCESS) continue
			val level = result.batteryLevel ?: continue
			if (level !in 0f..1f) continue

			battery[trackerId] = (level * 100f).roundToInt().coerceIn(0, 100)
		}
		return battery
	}
}
