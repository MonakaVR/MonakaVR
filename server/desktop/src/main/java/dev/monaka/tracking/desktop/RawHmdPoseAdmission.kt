package dev.monaka.tracking.desktop

import dev.monaka.protocol.v2.CoordinateSpace
import dev.monaka.tracking.ObservationSampleProvenance
import dev.monaka.tracking.RawHmdPoseInput
import dev.monaka.tracking.TrackingModality
import dev.slimevr.desktop.platform.ProtobufMessages.Position
import dev.slimevr.desktop.platform.TransportSessionHandle
import java.util.Collections
import java.util.EnumSet

enum class RawHmdFrameProofKind { NO_AUTHORITATIVE_FRAME_PROOF, EXPLICIT_POSE_BOUND_FRAME_PROOF }

/** Explicit caller policy, not the unrelated MTP lease. Invalid policies reject at admission. */
data class RawHmdPoseFreshnessPolicy(val maxReceiptAgeNanos: Long)

enum class RawHmdPoseInputRejectionReason(val code: String) {
	CAPABILITY_UNAVAILABLE("hmd_capability_unavailable"),
	FRAME_REFERENCE_UNAVAILABLE("hmd_frame_reference_unavailable"),
	FRAME_SPACE_UNAVAILABLE("hmd_frame_space_unavailable"),
	FRAME_SPACE_INVALID("hmd_frame_space_invalid"),
	FRAME_EPOCH_UNAVAILABLE("hmd_frame_epoch_unavailable"),
	POSITION_UNAVAILABLE("hmd_position_unavailable"),
	SOURCE_IDENTITY_MISMATCH("hmd_source_identity_mismatch"),
	SOURCE_EPOCH_MISMATCH("hmd_source_epoch_mismatch"),
	SESSION_EPOCH_UNAVAILABLE("hmd_session_epoch_unavailable"),
	SESSION_EPOCH_MISMATCH("hmd_session_epoch_mismatch"),
	CURRENT_SESSION_CHANGED("hmd_current_session_changed"),
	SAMPLE_SEQUENCE_MISMATCH("hmd_sample_sequence_mismatch"),
	SAMPLE_PROVENANCE_INVALID("hmd_sample_provenance_invalid"),
	POSITION_COMPONENTS_INCOMPLETE("hmd_position_components_incomplete"),
	POSITION_NONFINITE("hmd_position_nonfinite"),
	ORIENTATION_INVALID("hmd_orientation_invalid"),
	POSITION_MODALITY_NOT_FULL("hmd_position_modality_not_full"),
	DATA_SOURCE_UNAVAILABLE("hmd_data_source_unavailable"),
	DATA_SOURCE_UNSUPPORTED("hmd_data_source_unsupported"),
	RECEIPT_TIME_INVALID("hmd_receipt_time_invalid"),
	FUTURE_RECEIPT_TIME("hmd_future_receipt_time"),
	SAMPLE_STALE("hmd_sample_stale"),
	FRESHNESS_POLICY_INVALID("hmd_freshness_policy_invalid"),
	MAPPING_REVISION_INVALID("hmd_mapping_revision_invalid"),
	FEEDBACK_SOURCE_NOT_ALLOWED("hmd_feedback_source_not_allowed"),
}

/** Evidence for admission, never a feature toggle or an ambient session-wide trust declaration. */
sealed interface RawHmdPoseInputCapability {
	class Unavailable(reasons: Set<RawHmdPoseInputRejectionReason>) : RawHmdPoseInputCapability {
		val reasons = immutableReasons(reasons)
		init { require(reasons.isNotEmpty()) }
	}

	/** Internal producers must supply authoritative pose-bound facts. Current production has none. */
	@ConsistentCopyVisibility
	data class Ready internal constructor(
		val expectedSourceId: String,
		val expectedSourceEpoch: String,
		val expectedTransportSessionEpoch: String,
		val expectedSampleSequence: Long,
		val space: CoordinateSpace?,
		val frameCalibrationEpoch: String?,
		val mappingRevision: Long?,
		val freshnessPolicy: RawHmdPoseFreshnessPolicy,
		val proofKind: RawHmdFrameProofKind,
		val proofSource: String?,
	) : RawHmdPoseInputCapability
}

sealed interface RawHmdPoseInputAdmission {
	@ConsistentCopyVisibility
	data class Accepted internal constructor(val input: RawHmdPoseInput) : RawHmdPoseInputAdmission

	class Rejected internal constructor(reasons: Set<RawHmdPoseInputRejectionReason>) : RawHmdPoseInputAdmission {
		val reasons = immutableReasons(reasons)
		init { require(reasons.isNotEmpty()) }
	}
}

private fun immutableReasons(reasons: Set<RawHmdPoseInputRejectionReason>): Set<RawHmdPoseInputRejectionReason> =
	Collections.unmodifiableSet(EnumSet.noneOf(RawHmdPoseInputRejectionReason::class.java).also { it.addAll(reasons) })

/**
 * One read-side boundary. Readers are supplied by ProtobufBridge, not by public callers.
 * The clock is the bridge's local monotonic ingress clock; acquisition time remains unknown.
 * No accepted input is allocated before the final active-handle identity check.
 */
internal fun admitRawHmdPoseInput(
	capability: RawHmdPoseInputCapability,
	readActiveSession: () -> TransportSessionHandle?,
	readCurrentPose: (String) -> HmdAcceptedPoseMessageSample?,
	nowSystemNanos: () -> Long,
): RawHmdPoseInputAdmission {
	val reasons = linkedSetOf<RawHmdPoseInputRejectionReason>()
	val before = readActiveSession()
	val sample = before?.let { readCurrentPose(it.epoch) }
	val ready = when (capability) {
		is RawHmdPoseInputCapability.Unavailable -> {
			reasons += RawHmdPoseInputRejectionReason.CAPABILITY_UNAVAILABLE
			reasons += capability.reasons
			null
		}
		is RawHmdPoseInputCapability.Ready -> capability
	}
	if (before == null) reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_UNAVAILABLE
	if (sample == null) reasons += RawHmdPoseInputRejectionReason.POSITION_UNAVAILABLE
	if (ready != null) {
		if (ready.proofKind != RawHmdFrameProofKind.EXPLICIT_POSE_BOUND_FRAME_PROOF || ready.proofSource.isNullOrBlank())
			reasons += RawHmdPoseInputRejectionReason.FRAME_REFERENCE_UNAVAILABLE
		if (ready.space == null) reasons += RawHmdPoseInputRejectionReason.FRAME_SPACE_UNAVAILABLE
		else if (ready.space.id.isBlank() || ready.space.convention.isBlank() || ready.space.revision < 0)
			reasons += RawHmdPoseInputRejectionReason.FRAME_SPACE_INVALID
		if (ready.frameCalibrationEpoch.isNullOrBlank()) reasons += RawHmdPoseInputRejectionReason.FRAME_EPOCH_UNAVAILABLE
		if (ready.freshnessPolicy.maxReceiptAgeNanos <= 0) reasons += RawHmdPoseInputRejectionReason.FRESHNESS_POLICY_INVALID
		if (ready.mappingRevision != null && ready.mappingRevision < 0) reasons += RawHmdPoseInputRejectionReason.MAPPING_REVISION_INVALID
		if (ready.expectedTransportSessionEpoch.isBlank()) reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_UNAVAILABLE
		if (before != null && before.epoch != ready.expectedTransportSessionEpoch)
			reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_MISMATCH
	}
	if (sample != null) {
		if (!sample.ingressIdentity.isRawHmd()) reasons += RawHmdPoseInputRejectionReason.FEEDBACK_SOURCE_NOT_ALLOWED
		if (sample.sequence < 0 || sample.sourceEpoch.isBlank()) reasons += RawHmdPoseInputRejectionReason.SAMPLE_PROVENANCE_INVALID
		if (sample.transportSessionEpoch == null) reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_UNAVAILABLE
		else if (sample.transportSessionEpoch != before.epoch) reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_MISMATCH
		for (reason in sample.structuralRejectionReasons) {
			reasons += when (reason) {
				"hmd_position_components_incomplete" -> RawHmdPoseInputRejectionReason.POSITION_COMPONENTS_INCOMPLETE
				"hmd_position_nonfinite" -> RawHmdPoseInputRejectionReason.POSITION_NONFINITE
				"hmd_orientation_invalid" -> RawHmdPoseInputRejectionReason.ORIENTATION_INVALID
				else -> RawHmdPoseInputRejectionReason.SAMPLE_PROVENANCE_INVALID
			}
		}
		if (!sample.dataSourcePresent) reasons += RawHmdPoseInputRejectionReason.DATA_SOURCE_UNAVAILABLE
		else if (Position.DataSource.forNumber(sample.dataSourceValue) == null)
			reasons += RawHmdPoseInputRejectionReason.DATA_SOURCE_UNSUPPORTED
		if (sample.modality != TrackingModality.FULL || sample.dataSourceValue != Position.DataSource.FULL.number)
			reasons += RawHmdPoseInputRejectionReason.POSITION_MODALITY_NOT_FULL
		if (ready != null) {
			if (sample.ingressIdentity.sourceId != ready.expectedSourceId) reasons += RawHmdPoseInputRejectionReason.SOURCE_IDENTITY_MISMATCH
			if (sample.sourceEpoch != ready.expectedSourceEpoch) reasons += RawHmdPoseInputRejectionReason.SOURCE_EPOCH_MISMATCH
			if (sample.transportSessionEpoch != null && sample.transportSessionEpoch != ready.expectedTransportSessionEpoch)
				reasons += RawHmdPoseInputRejectionReason.SESSION_EPOCH_MISMATCH
			if (sample.sequence != ready.expectedSampleSequence) reasons += RawHmdPoseInputRejectionReason.SAMPLE_SEQUENCE_MISMATCH
			val now = nowSystemNanos()
			val receipt = sample.receivedAtSystemNanos
			// The existing core provenance requires nonnegative local time. Check before subtraction.
			if (now < 0 || receipt < 0) reasons += RawHmdPoseInputRejectionReason.RECEIPT_TIME_INVALID
			else if (now < receipt) reasons += RawHmdPoseInputRejectionReason.FUTURE_RECEIPT_TIME
			else if (ready.freshnessPolicy.maxReceiptAgeNanos > 0 && now - receipt > ready.freshnessPolicy.maxReceiptAgeNanos)
				reasons += RawHmdPoseInputRejectionReason.SAMPLE_STALE
		}
	}
	if (readActiveSession() !== before) reasons += RawHmdPoseInputRejectionReason.CURRENT_SESSION_CHANGED
	if (reasons.isNotEmpty()) return RawHmdPoseInputAdmission.Rejected(reasons)
	checkNotNull(ready)
	checkNotNull(sample)
	val space = checkNotNull(ready.space)
	val provenance = ObservationSampleProvenance(sample.sequence, sample.receivedAtSystemNanos,
		sample.sourceEpoch, checkNotNull(ready.frameCalibrationEpoch), ready.mappingRevision, space)
	return RawHmdPoseInputAdmission.Accepted(RawHmdPoseInput(
		sample.ingressIdentity, sample.position, sample.orientation, space, provenance,
	))
}
