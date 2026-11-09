package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrackingOverrideHand(val serial: String, val targetPath: String) {
	LEFT("monaka-override-v1-left", "/user/hand/left"),
	RIGHT("monaka-override-v1-right", "/user/hand/right"),
	;
	val devicePath: String get() = "/devices/slimevr/$serial"
}

data class TrackingOverrideSelection(
	val enabled: Boolean = false,
	val source: TrackerReference? = null,
	val gripOffset: TrackingOverrideGripOffset? = null,
	val sameUniverseConfirmed: Boolean = false,
)

/** Tracker-local grip reference transform. Identity is an explicit choice. */
data class TrackingOverrideGripOffset(
	val position: Vector3 = Vector3(0f, 0f, 0f),
	val rotation: Quaternion = Quaternion.IDENTITY,
) {
	init {
		require(position.isFinite() && rotation.isUnitRotation()) { "Grip offset requires finite metres and a unit quaternion" }
	}
}

enum class TrackingOverrideSourceStatus {
	OFF, UNASSIGNED, NO_GRIP_OFFSET, UNCONFIRMED_UNIVERSE, CAPABILITY_UNKNOWN, CAPABILITY_UNSUPPORTED,
	SOURCE_UNAVAILABLE, STALE, INCOMPLETE, SPACE_MISMATCH, FULL,
}

/** An independent hand output. No body role, fallback, or IK output participates. */
data class TrackingOverrideFrame(val status: TrackingOverrideSourceStatus, val pose: TrackingOverridePose? = null)

data class TrackingOverridePose(val position: Vector3, val rotation: Quaternion, val remainingFreshNanos: Long)

internal class TrackingOverrideResolver(
	private val backend: MtpObservationBackend,
	private val expectedSpace: CoordinateSpace,
	private val timeoutNanos: Long,
) {
	private val adapter = MtpPoseAdapter()

	fun resolve(selection: TrackingOverrideSelection?, nowNanos: Long, paused: Boolean): TrackingOverrideFrame {
		if (selection?.enabled != true) return TrackingOverrideFrame(TrackingOverrideSourceStatus.OFF)
		val source = selection.source ?: return TrackingOverrideFrame(TrackingOverrideSourceStatus.UNASSIGNED)
		val key = source.mtp ?: return TrackingOverrideFrame(TrackingOverrideSourceStatus.CAPABILITY_UNSUPPORTED)
		val offset = selection.gripOffset ?: return TrackingOverrideFrame(TrackingOverrideSourceStatus.NO_GRIP_OFFSET)
		if (!selection.sameUniverseConfirmed) return TrackingOverrideFrame(TrackingOverrideSourceStatus.UNCONFIRMED_UNIVERSE)
		val declared = backend.declaredCapabilitiesFor(key)
			?: return TrackingOverrideFrame(TrackingOverrideSourceStatus.CAPABILITY_UNKNOWN)
		if (!declared.supportsSixDof) return TrackingOverrideFrame(TrackingOverrideSourceStatus.CAPABILITY_UNSUPPORTED)
		val sample = backend.sampleFor(key)
		if (paused || sample == null) return TrackingOverrideFrame(TrackingOverrideSourceStatus.SOURCE_UNAVAILABLE)
		if (sample.pose.coordinate_space != expectedSpace) return TrackingOverrideFrame(TrackingOverrideSourceStatus.SPACE_MISMATCH)
		val age = nowNanos - sample.sampleTime
		if (age < 0 || age >= timeoutNanos) return TrackingOverrideFrame(TrackingOverrideSourceStatus.STALE)
		if (sample.pose.modality != "full") return TrackingOverrideFrame(TrackingOverrideSourceStatus.INCOMPLETE)

		// The existing admission adapter owns component validity and Float conversion.
		// Its observation label has no relationship to the dedicated wire device.
		val adapted = runCatching { adapter.adapt(sample.pose, TrackerPosition.HEAD, sample.sampleTime) }.getOrNull()
		val p = adapted?.position
		val q = adapted?.rotation
		if (p == null || q == null || !q.isUnitRotation()) return TrackingOverrideFrame(TrackingOverrideSourceStatus.INCOMPLETE)
		// The wire permits a small unit-norm tolerance. After validation, use
		// the same rotations at unit scale for the rigid transform.
		val sourceRotation = q.unit()
		val position = p + sourceRotation.sandwich(offset.position)
		val rotation = (sourceRotation * offset.rotation.unit()).unit()
		if (!position.isFinite() || !rotation.isUnitRotation()) return TrackingOverrideFrame(TrackingOverrideSourceStatus.INCOMPLETE)
		return TrackingOverrideFrame(TrackingOverrideSourceStatus.FULL, TrackingOverridePose(position, rotation, timeoutNanos - age))
	}
}

private fun Vector3.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
// One Float roundoff allowance after the protocol's double-precision unit-norm validation.
private fun Quaternion.isUnitRotation() = abs(sqrt(w * w + x * x + y * y + z * z) - 1f) <= 1e-5f + Math.ulp(1f)
