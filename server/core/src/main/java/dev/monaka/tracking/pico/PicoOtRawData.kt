package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/**
 * Backend-neutral tracking state exported by a PICO OT transport/utility layer.
 *
 * State is component-specific so visual position can be lost while IMU-backed
 * rotation remains usable.
 */
enum class PicoOtComponentTrackingState {
	TRACKED,
	DEGRADED,
	LOST,
	UNAVAILABLE,
}

/**
 * Raw per-tracker state delivered to Monaka by a PICO OT transport.
 *
 * [batteryPercent] is diagnostic metadata and is intentionally not consumed by the
 * constraint resolver. Snapshot omission represents source disappearance.
 */
data class PicoOtTrackerSample(
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
	val batteryPercent: Int? = null,
) {
	init {
		require(trackerId.isNotBlank()) { "trackerId must not be blank" }
		require(batteryPercent == null || batteryPercent in 0..100) {
			"batteryPercent must be between 0 and 100"
		}
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
 * Complete set of PICO OT trackers currently known by the transport.
 * Tracker ids must be stable and unique within a snapshot.
 */
data class PicoOtSnapshot(
	val trackers: List<PicoOtTrackerSample>,
) {
	init {
		val ids = trackers.map { it.trackerId }
		require(ids.size == ids.toSet().size) { "PICO OT snapshot contains duplicate tracker ids" }
	}
}

/**
 * Transport boundary implemented by the actual PICO Utility/driver layer.
 *
 * [snapshot] receives the current Monaka monotonic poll time. Implementations that
 * replay the same transport frame across multiple polls should override
 * [observationTimestampNanos] so repeated polls do not make a frozen pose appear
 * fresh forever.
 */
fun interface PicoOtDataSource {
	fun snapshot(observedAtNanos: Long): PicoOtSnapshot

	fun observationTimestampNanos(requestedAtNanos: Long): Long = requestedAtNanos
}
