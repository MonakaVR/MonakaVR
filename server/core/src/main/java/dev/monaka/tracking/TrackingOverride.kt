package dev.monaka.tracking

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.mtp.MtpObservationBackend
import dev.monaka.tracking.mtp.MtpPoseAdapter
import dev.slimevr.tracking.trackers.TrackerPosition
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrackingOverrideHand(val body: TrackerPosition, val serial: String, val targetPath: String) {
	LEFT(TrackerPosition.LEFT_HAND, "monaka-override-v1-left", "/user/hand/left"),
	RIGHT(TrackerPosition.RIGHT_HAND, "monaka-override-v1-right", "/user/hand/right"),
	;
	val devicePath: String get() = "/devices/slimevr/$serial"
}

data class TrackingOverrideSelection(
	val enabled: Boolean = false,
	val gripOffset: TrackingOverrideGripOffset? = null,
	val confirmedAssignment: String? = null,
) {
	init {
		require(confirmedAssignment == null || (gripOffset != null && confirmedAssignment.matches(Regex("[0-9a-f]{64}"))))
	}

	internal fun calibrationCurrent(hand: TrackingOverrideHand, source: TrackerReference?, space: CoordinateSpace): Boolean =
		confirmedAssignment != null && source?.mtp != null && confirmedAssignment == hand.assignmentFingerprint(source, space)

	companion object {
		fun confirmed(
			enabled: Boolean,
			hand: TrackingOverrideHand,
			source: TrackerReference,
			space: CoordinateSpace,
			gripOffset: TrackingOverrideGripOffset,
		) = TrackingOverrideSelection(enabled, gripOffset, hand.assignmentFingerprint(source, space))
	}
}

/** An explicit cleared 6DoF field never inherits the Q-only Main or fallback. */
internal fun MainTrackerAssignment.configuredSixDofSource(): TrackerReference? =
	if (sourceSelection == null) mainTracker else sourceSelection.sixDofSource

private fun TrackingOverrideHand.assignmentFingerprint(source: TrackerReference, space: CoordinateSpace): String {
	require(source.mtp != null)
	val fields = listOf(serial, source.observationId, space.id, space.convention, space.revision.toString())
	val canonical = fields.joinToString("") { "${it.length}:$it" }
	return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).toHexString()
}

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
data class TrackingOverrideFrame(
	val status: TrackingOverrideSourceStatus,
	val pose: TrackingOverridePose? = null,
	val source: TrackerReference? = null,
)

data class TrackingOverridePose(val position: Vector3, val rotation: Quaternion, val remainingFreshNanos: Long)

internal class TrackingOverrideResolver(
	private val backend: MtpObservationBackend,
	private val expectedSpace: CoordinateSpace,
	private val timeoutNanos: Long,
) {
	private val adapter = MtpPoseAdapter()
	private val assignmentFingerprints = mutableMapOf<TrackingOverrideHand, Pair<TrackerReference, String>>()

	private fun fingerprint(hand: TrackingOverrideHand, source: TrackerReference): String {
		val current = assignmentFingerprints[hand]
		if (current?.first == source) return current.second
		val next = hand.assignmentFingerprint(source, expectedSpace)
		assignmentFingerprints[hand] = source to next
		return next
	}

	fun resolve(
		hand: TrackingOverrideHand,
		selection: TrackingOverrideSelection?,
		source: TrackerReference?,
		nowNanos: Long,
		paused: Boolean,
	): TrackingOverrideFrame {
		fun unavailable(status: TrackingOverrideSourceStatus) = TrackingOverrideFrame(status, source = source)
		if (selection?.enabled != true) return unavailable(TrackingOverrideSourceStatus.OFF)
		if (source == null) return unavailable(TrackingOverrideSourceStatus.UNASSIGNED)
		val key = source.mtp ?: return unavailable(TrackingOverrideSourceStatus.CAPABILITY_UNSUPPORTED)
		val offset = selection.gripOffset ?: return unavailable(TrackingOverrideSourceStatus.NO_GRIP_OFFSET)
		if (selection.confirmedAssignment == null || selection.confirmedAssignment != fingerprint(hand, source)) {
			return unavailable(TrackingOverrideSourceStatus.UNCONFIRMED_UNIVERSE)
		}
		val declared = backend.declaredCapabilitiesFor(key)
			?: return unavailable(TrackingOverrideSourceStatus.CAPABILITY_UNKNOWN)
		if (!declared.supportsSixDof) return unavailable(TrackingOverrideSourceStatus.CAPABILITY_UNSUPPORTED)
		val sample = backend.sampleFor(key)
		if (paused || sample == null) return unavailable(TrackingOverrideSourceStatus.SOURCE_UNAVAILABLE)
		if (sample.pose.convention != expectedSpace.convention) {
			return unavailable(TrackingOverrideSourceStatus.SPACE_MISMATCH)
		}
		val age = nowNanos - sample.sampleTime
		if (age < 0 || age >= timeoutNanos) return unavailable(TrackingOverrideSourceStatus.STALE)
		if (sample.pose.modality != "full") return unavailable(TrackingOverrideSourceStatus.INCOMPLETE)

		// The existing admission adapter owns component validity and Float conversion.
		// Its observation label has no relationship to the dedicated wire device.
		val adapted = runCatching { adapter.adapt(sample, hand.body) }.getOrNull()
		val p = adapted?.position
		val q = adapted?.rotation
		if (p == null || q == null || !q.isUnitRotation()) return unavailable(TrackingOverrideSourceStatus.INCOMPLETE)
		// The wire permits a small unit-norm tolerance. After validation, use
		// the same rotations at unit scale for the rigid transform.
		val sourceRotation = q.unit()
		val position = p + sourceRotation.sandwich(offset.position)
		val rotation = (sourceRotation * offset.rotation.unit()).unit()
		if (!position.isFinite() || !rotation.isUnitRotation()) return unavailable(TrackingOverrideSourceStatus.INCOMPLETE)
		return TrackingOverrideFrame(TrackingOverrideSourceStatus.FULL, TrackingOverridePose(position, rotation, timeoutNanos - age), source)
	}
}

private fun Vector3.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
// One Float roundoff allowance after the protocol's double-precision unit-norm validation.
private fun Quaternion.isUnitRotation() = abs(sqrt(w * w + x * x + y * y + z * z) - 1f) <= 1e-5f + Math.ulp(1f)
