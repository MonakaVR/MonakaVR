package dev.monaka.tracking.pico

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

/** Reference frames currently exported by PicoMotionTrackerBridge. */
enum class PicoMotionTrackerBridgeReferenceFrame {
	PICO_OUTPUT_A,
}

/**
 * Monaka-facing mirror of the normalized PicoMotionTrackerBridge receiver state.
 *
 * [serial] is the persistent physical identity. [connectedSnapshot] is retained for
 * diagnostics only: PicoMotionTrackerBridge documents it as an enumeration-time
 * snapshot rather than a live presence signal, so Monaka must not use it to decide
 * whether a source exists.
 *
 * PicoMotionTrackerBridge always has [lastPoseReceivePcMonotonicNanos] after a pose
 * packet has reached the PC receiver. [mappedPosePcMonotonicNanos] becomes available
 * when bridge clock synchronization can map the HMD/source timestamp into the PC
 * monotonic clock domain.
 *
 * Monaka intentionally uses the immutable local receive timestamp for observation
 * freshness and ordering. The bridge recomputes mapped source timestamps when its clock
 * estimate changes, so using the mapped value as an observation timestamp could make an
 * already-received pose appear newer without a new pose packet. The mapped timestamp is
 * retained as metadata for later prediction/latency work.
 */
data class PicoMotionTrackerBridgeState(
	val serial: String,
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
		require(serial.isNotBlank()) { "PicoMotionTrackerBridge serial must not be blank" }
		require(lastPoseReceivePcMonotonicNanos >= 0L) {
			"lastPoseReceivePcMonotonicNanos must be non-negative"
		}
		require(mappedPosePcMonotonicNanos == null || mappedPosePcMonotonicNanos >= 0L) {
			"mappedPosePcMonotonicNanos must be non-negative"
		}
		require(!positionValid || positionMeters != null) {
			"positionValid requires a position sample"
		}
		require(!orientationSamplePresent || orientation != null) {
			"orientationSamplePresent requires an orientation sample"
		}
	}

	/** Immutable PC receive time used by Monaka freshness and source ordering. */
	val observationPcMonotonicNanos: Long
		get() = lastPoseReceivePcMonotonicNanos
}

/** Complete set currently retained by the PicoMotionTrackerBridge PC receiver. */
fun interface PicoMotionTrackerBridgeStateProvider {
	fun snapshot(): List<PicoMotionTrackerBridgeState>
}

/** Pose after the bridge-specific reference frame has been converted to Monaka space. */
data class PicoMotionTrackerBridgeMappedPose(
	val position: Vector3?,
	val rotation: Quaternion?,
)

/**
 * Explicit coordinate conversion boundary.
 *
 * There is intentionally no default identity mapping: PicoMotionTrackerBridge exports
 * PICO OutputA, not Unity world space, and coordinate policy belongs above its backend.
 */
fun interface PicoMotionTrackerBridgePoseMapper {
	fun toMonaka(state: PicoMotionTrackerBridgeState): PicoMotionTrackerBridgeMappedPose
}

/**
 * Converts the hardware-validated PicoMotionTrackerBridge state model into the existing
 * Monaka PICO observation contract.
 *
 * Quality policy follows the semantics recovered by PicoMotionTrackerBridge:
 * - optical position valid -> position TRACKED;
 * - optical position invalid -> position LOST even if numeric position changes;
 * - sane orientation while position is valid -> rotation TRACKED;
 * - sane orientation continuing through optical loss -> rotation DEGRADED;
 * - no sane orientation sample -> rotation LOST.
 *
 * The provider's returned serial set is authoritative. [PicoMotionTrackerBridgeState.connectedSnapshot]
 * is not used as a live connection gate because the bridge explicitly documents that it is not one.
 */
class PicoMotionTrackerBridgeDataSource(
	private val provider: PicoMotionTrackerBridgeStateProvider,
	private val poseMapper: PicoMotionTrackerBridgePoseMapper,
) : PicoOtDataSource {
	override fun snapshot(observedAtNanos: Long): PicoOtSnapshot {
		require(observedAtNanos >= 0L) { "observedAtNanos must be non-negative" }

		val states = provider.snapshot()
		val serials = states.map { it.serial }
		require(serials.size == serials.toSet().size) {
			"PicoMotionTrackerBridge snapshot contains duplicate serials"
		}

		return PicoOtSnapshot(
			states.map { state ->
				val mapped = poseMapper.toMonaka(state)
				require(!state.positionValid || mapped.position != null) {
					"mapped position must be present when bridge position is valid"
				}
				require(!state.orientationSamplePresent || mapped.rotation != null) {
					"mapped rotation must be present when bridge orientation sample is present"
				}

				PicoOtTrackerSample(
					trackerId = state.serial,
					position = mapped.position,
					rotation = mapped.rotation,
					positionState = if (state.positionValid) {
						PicoOtComponentTrackingState.TRACKED
					} else {
						PicoOtComponentTrackingState.LOST
					},
					rotationState = when {
						!state.orientationSamplePresent -> PicoOtComponentTrackingState.LOST
						state.positionValid -> PicoOtComponentTrackingState.TRACKED
						else -> PicoOtComponentTrackingState.DEGRADED
					},
					observedAtNanos = state.observationPcMonotonicNanos,
				)
			},
		)
	}
}
