package dev.monaka.tracking.mtp

import dev.monaka.protocol.v2.MtpPose
import dev.monaka.protocol.v2.MtpTrackerState
import dev.monaka.protocol.v3.MtpPose as C3MtpPose
import dev.monaka.protocol.v3.MtpTrackerState as C3MtpTrackerState

internal const val MTP_CONVENTION = "rh_y_up_neg_z_forward"

/** Receiver-facing values after validation by the codec for the sender's wire major. */
sealed interface NormalMtpMessage {
	val publisher_id: String
	val source_id: String
	val tracker_id: String
	val session_id: String
	val clock_id: String
	val sequence: Long
	val timestamp_ns: Long
	val convention: String
	val tracking_state: String
	val capabilities: List<String>
}

@ConsistentCopyVisibility
data class NormalMtpInput internal constructor(
	val source_id: String,
	val device_id: String,
	val session_id: String,
	val sequence: Long,
	val orientation_evidence: String,
) {
	internal fun sameAcquisition(other: NormalMtpInput): Boolean =
		source_id == other.source_id && device_id == other.device_id && session_id == other.session_id && sequence == other.sequence
}

@ConsistentCopyVisibility
data class NormalMtpPose internal constructor(
	override val publisher_id: String,
	override val source_id: String,
	override val tracker_id: String,
	override val session_id: String,
	override val clock_id: String,
	override val sequence: Long,
	override val timestamp_ns: Long,
	override val convention: String,
	override val tracking_state: String,
	override val capabilities: List<String>,
	val sent_at_ns: Long,
	val timestamp_kind: String,
	val position: List<Double>?,
	val orientation: List<Double>?,
	val positionValid: Boolean,
	val orientationValid: Boolean,
	val positionConfidence: Double,
	val orientationConfidence: Double,
	val modality: String,
	val input: NormalMtpInput,
) : NormalMtpMessage

internal data class NormalMtpTrackerState(
	override val publisher_id: String,
	override val source_id: String,
	override val tracker_id: String,
	override val session_id: String,
	override val clock_id: String,
	override val sequence: Long,
	override val timestamp_ns: Long,
	override val convention: String,
	override val tracking_state: String,
	override val capabilities: List<String>,
	val presence: String,
) : NormalMtpMessage

internal fun MtpPose.toNormalMtpPose() = NormalMtpPose(
	publisher_id, source_id, tracker_id, session_id, clock_id, sequence, timestamp_ns,
	coordinate_space.convention, tracking_state, capabilities, sent_at_ns, timestamp_kind,
	position, orientation, validity.position, validity.orientation, confidence.position, confidence.orientation,
	modality, NormalMtpInput(input.source_id, input.device_id, input.session_id, input.sequence, input.orientation_evidence),
)

internal fun MtpTrackerState.toNormalMtpTrackerState() = NormalMtpTrackerState(
	publisher_id, source_id, tracker_id, session_id, clock_id, sequence, timestamp_ns,
	coordinate_space.convention, tracking_state, capabilities, presence,
)

internal fun C3MtpPose.toNormalMtpPose() = NormalMtpPose(
	publisher_id, source_id, tracker_id, session_id, clock_id, sequence, timestamp_ns,
	convention, tracking_state, capabilities, sent_at_ns, timestamp_kind,
	position, orientation, validity.position, validity.orientation, confidence.position, confidence.orientation,
	modality, NormalMtpInput(input.source_id, input.device_id, input.session_id, input.sequence, input.orientation_evidence),
)

internal fun C3MtpTrackerState.toNormalMtpTrackerState() = NormalMtpTrackerState(
	publisher_id, source_id, tracker_id, session_id, clock_id, sequence, timestamp_ns,
	convention, tracking_state, capabilities, presence,
)
