package dev.monaka.protocol.v2

sealed interface Envelope
data class Version(
    val major: Int,
    val minor: Int,
)

data class CoordinateSpace(
    val id: String,
    val convention: String,
    val revision: Long,
)

data class Battery(
    val fraction: Double?,
    val charging: Boolean?,
    val timestamp_ns: Long,
)

data class Derivative(
    val value: List<Double>,
    val frame: String,
    val evidence: String,
)

data class Validity(
    val position: Boolean,
    val orientation: Boolean,
)

data class Confidence(
    val position: Double,
    val orientation: Double,
)

data class Input(
    val source_id: String,
    val device_id: String,
    val session_id: String,
    val sequence: Long,
    val orientation_evidence: String,
)

data class TrackerObservation(
    val version: Version,
    val source_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val device_id: String,
    val position: List<Double>?,
    val orientation: List<Double>?,
    val validity: Validity,
    val orientation_evidence: String,
    val linear_velocity: Derivative? = null,
    val angular_velocity: Derivative? = null,
    val linear_acceleration: Derivative? = null,
    val tracking_state: String,
    val coordinate_space: CoordinateSpace,
    val capabilities: List<String>,
    val modality: String,
    val battery: Battery?,
) : Envelope

data class ObservationDeviceState(
    val version: Version,
    val source_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val device_id: String,
    val presence: String,
    val tracking_state: String,
    val coordinate_space: CoordinateSpace,
    val capabilities: List<String>,
    val modality: String,
    val battery: Battery?,
) : Envelope

data class MtpPose(
    val version: Version,
    val source_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val tracker_id: String,
    val position: List<Double>?,
    val orientation: List<Double>?,
    val validity: Validity,
    val linear_velocity: List<Double>? = null,
    val angular_velocity: List<Double>? = null,
    val linear_acceleration: List<Double>? = null,
    val confidence: Confidence,
    val tracking_state: String,
    val coordinate_space: CoordinateSpace,
    val capabilities: List<String>,
    val modality: String,
    val publisher_id: String,
    val mapping_revision: Long,
    val input: Input,
) : Envelope

data class MtpTrackerState(
    val version: Version,
    val source_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val tracker_id: String,
    val presence: String,
    val tracking_state: String,
    val coordinate_space: CoordinateSpace,
    val capabilities: List<String>,
    val modality: String,
    val publisher_id: String,
    val battery: Battery?,
    val mapping_revision: Long,
) : Envelope

data class AuthorityHeader(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
)

data class SourceSpaceAuthority(
    val source_id: String,
    val source_authority_session_epoch: String,
    val source_space_id: String,
    val source_space_generation: Long,
)

data class SourceObservationIdentity(
    val source_space: SourceSpaceAuthority,
    val observation_id: Long,
    val source_locate_time_ns: Long,
    val source_time_domain_id: String,
)

data class HmdValidityEvidence(
    val position_valid: Boolean,
    val orientation_valid: Boolean,
    val position_tracked: Boolean,
    val orientation_tracked: Boolean,
    val view_position_valid: Boolean,
    val view_orientation_valid: Boolean,
)

data class RigidTransform(
    val rotation_xyzw: List<Double>,
    val translation_xyz: List<Double>,
)

data class CommonWorldReference(
    val owner_id: String,
    val world_epoch: String,
    val coordinate_space: CoordinateSpace,
)

data class CommonMappingReference(
    val world: CommonWorldReference,
    val source_space: SourceSpaceAuthority,
    val calibration_epoch: String,
    val mapping_revision: Long,
)

data class TrustedHmdSourceAuthority(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val source_space: SourceSpaceAuthority,
    val source_space_kind: String,
    val source_time_domain_id: String,
) : Envelope

data class TrustedHmdSourcePose(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val source: SourceObservationIdentity,
    val position: List<Double>,
    val orientation: List<Double>,
    val validity: HmdValidityEvidence,
) : Envelope

data class TrustedHmdSourceUnavailable(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val source_space: SourceSpaceAuthority,
    val reason: String,
    val validity: HmdValidityEvidence?,
) : Envelope

data class TrustedHmdSourceRevocation(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val source_space: SourceSpaceAuthority,
    val reason: String,
) : Envelope

data class CommonWorldAuthorityPublication(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val world: CommonWorldReference,
    val anchor_source: SourceSpaceAuthority,
) : Envelope

data class CommonWorldMappingPublication(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val mapping: CommonMappingReference,
    val transform: RigidTransform,
) : Envelope

data class TrustedHmdCommonPose(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val mapping: CommonMappingReference,
    val source: SourceObservationIdentity,
    val source_position: List<Double>,
    val source_orientation: List<Double>,
    val validity: HmdValidityEvidence,
    val common_position: List<Double>,
    val common_orientation: List<Double>,
) : Envelope

data class TrustedHmdCommonUnavailable(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val mapping: CommonMappingReference,
    val reason: String,
    val validity: HmdValidityEvidence?,
) : Envelope

data class CommonWorldMappingRevocation(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val mapping: CommonMappingReference,
    val reason: String,
) : Envelope

data class CommonWorldRevocation(
    val version: Version,
    val publisher_id: String,
    val session_id: String,
    val clock_id: String,
    val sequence: Long,
    val timestamp_ns: Long,
    val sent_at_ns: Long,
    val timestamp_kind: String,
    val world: CommonWorldReference,
    val reason: String,
) : Envelope
