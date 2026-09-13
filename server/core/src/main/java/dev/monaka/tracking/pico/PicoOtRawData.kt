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
 * constraint resolver. [observedAtNanos], when present, is already expressed in the
 * Monaka/PC monotonic clock domain and prevents repeated polling of a frozen source
 * from refreshing its freshness age. Snapshot omission represents source disappearance.
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
	val observedAtNanos: Long? = null,
) {
	init {
		require(trackerId.isNotBlank()) { "trackerId must not be blank" }
		require(batteryPercent == null || batteryPercent in 0..100) {
			"batteryPercent must be between 0 and 100"
		}
		require(observedAtNanos == null || observedAtNanos >= 0L) {
			"observedAtNanos must be non-negative"
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
 * The supplied timestamp is the Monaka monotonic receive/poll time. A data source
 * that already has a source observation time in this same clock domain should put it
 * on each [PicoOtTrackerSample.observedAtNanos].
 */
fun interface PicoOtDataSource {
	fun snapshot(observedAtNanos: Long): PicoOtSnapshot
}
