package dev.monaka.protocol.v1

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
    val device_id: String,
    val session_id: String,
    val sequence: Long,
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
    val battery: Battery?,
    val mapping_revision: Long,
) : Envelope

