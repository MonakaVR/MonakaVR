#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <variant>
#include <vector>
namespace monaka::protocol::v2 {
using Vec3 = std::array<double,3>;
using QuatXyzw = std::array<double,4>;
struct Version {
    std::uint16_t major{};
    std::uint16_t minor{};
};
struct CoordinateSpace {
    std::string id{};
    std::string convention{};
    std::uint32_t revision{};
};
struct Battery {
    std::optional<double> fraction{};
    std::optional<bool> charging{};
    std::int64_t timestamp_ns{};
};
struct Derivative {
    Vec3 value{};
    std::string frame{};
    std::string evidence{};
};
struct Validity {
    bool position{};
    bool orientation{};
};
struct Confidence {
    double position{};
    double orientation{};
};
struct Input {
    std::string source_id{};
    std::string device_id{};
    std::string session_id{};
    std::int64_t sequence{};
    std::string orientation_evidence{};
};
struct TrackerObservation {
    Version version{};
    std::string source_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    std::string device_id{};
    std::optional<Vec3> position{};
    std::optional<QuatXyzw> orientation{};
    Validity validity{};
    std::string orientation_evidence{};
    std::optional<Derivative> linear_velocity{};
    std::optional<Derivative> angular_velocity{};
    std::optional<Derivative> linear_acceleration{};
    std::string tracking_state{};
    CoordinateSpace coordinate_space{};
    std::vector<std::string> capabilities{};
    std::string modality{};
    std::optional<Battery> battery{};
};
struct ObservationDeviceState {
    Version version{};
    std::string source_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    std::string device_id{};
    std::string presence{};
    std::string tracking_state{};
    CoordinateSpace coordinate_space{};
    std::vector<std::string> capabilities{};
    std::string modality{};
    std::optional<Battery> battery{};
};
struct MtpPose {
    Version version{};
    std::string source_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    std::string tracker_id{};
    std::optional<Vec3> position{};
    std::optional<QuatXyzw> orientation{};
    Validity validity{};
    std::optional<Vec3> linear_velocity{};
    std::optional<Vec3> angular_velocity{};
    std::optional<Vec3> linear_acceleration{};
    Confidence confidence{};
    std::string tracking_state{};
    CoordinateSpace coordinate_space{};
    std::vector<std::string> capabilities{};
    std::string modality{};
    std::string publisher_id{};
    std::uint32_t mapping_revision{};
    Input input{};
};
struct MtpTrackerState {
    Version version{};
    std::string source_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    std::string tracker_id{};
    std::string presence{};
    std::string tracking_state{};
    CoordinateSpace coordinate_space{};
    std::vector<std::string> capabilities{};
    std::string modality{};
    std::string publisher_id{};
    std::optional<Battery> battery{};
    std::uint32_t mapping_revision{};
};
struct AuthorityHeader {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
};
struct SourceSpaceAuthority {
    std::string source_id{};
    std::string source_authority_session_epoch{};
    std::string source_space_id{};
    std::int64_t source_space_generation{};
};
struct SourceObservationIdentity {
    SourceSpaceAuthority source_space{};
    std::int64_t observation_id{};
    std::int64_t source_locate_time_ns{};
    std::string source_time_domain_id{};
};
struct HmdValidityEvidence {
    bool position_valid{};
    bool orientation_valid{};
    bool position_tracked{};
    bool orientation_tracked{};
    bool view_position_valid{};
    bool view_orientation_valid{};
};
struct RigidTransform {
    QuatXyzw rotation_xyzw{};
    Vec3 translation_xyz{};
};
struct CommonWorldReference {
    std::string owner_id{};
    std::string world_epoch{};
    CoordinateSpace coordinate_space{};
};
struct CommonMappingReference {
    CommonWorldReference world{};
    SourceSpaceAuthority source_space{};
    std::string calibration_epoch{};
    std::uint32_t mapping_revision{};
};
struct TrustedHmdSourceAuthority {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    SourceSpaceAuthority source_space{};
    std::string source_space_kind{};
    std::string source_time_domain_id{};
};
struct TrustedHmdSourcePose {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    SourceObservationIdentity source{};
    Vec3 position{};
    QuatXyzw orientation{};
    HmdValidityEvidence validity{};
};
struct TrustedHmdSourceUnavailable {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    SourceSpaceAuthority source_space{};
    std::string reason{};
    std::optional<HmdValidityEvidence> validity{};
};
struct TrustedHmdSourceRevocation {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    SourceSpaceAuthority source_space{};
    std::string reason{};
};
struct CommonWorldAuthorityPublication {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonWorldReference world{};
    SourceSpaceAuthority anchor_source{};
};
struct CommonWorldMappingPublication {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonMappingReference mapping{};
    RigidTransform transform{};
};
struct TrustedHmdCommonPose {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonMappingReference mapping{};
    SourceObservationIdentity source{};
    Vec3 source_position{};
    QuatXyzw source_orientation{};
    HmdValidityEvidence validity{};
    Vec3 common_position{};
    QuatXyzw common_orientation{};
};
struct TrustedHmdCommonUnavailable {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonMappingReference mapping{};
    std::string reason{};
    std::optional<HmdValidityEvidence> validity{};
};
struct CommonWorldMappingRevocation {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonMappingReference mapping{};
    std::string reason{};
};
struct CommonWorldRevocation {
    Version version{};
    std::string publisher_id{};
    std::string session_id{};
    std::string clock_id{};
    std::int64_t sequence{};
    std::int64_t timestamp_ns{};
    std::int64_t sent_at_ns{};
    std::string timestamp_kind{};
    CommonWorldReference world{};
    std::string reason{};
};
using Envelope = std::variant<TrackerObservation,ObservationDeviceState,MtpPose,MtpTrackerState,TrustedHmdSourceAuthority,TrustedHmdSourcePose,TrustedHmdSourceUnavailable,TrustedHmdSourceRevocation,CommonWorldAuthorityPublication,CommonWorldMappingPublication,TrustedHmdCommonPose,TrustedHmdCommonUnavailable,CommonWorldMappingRevocation,CommonWorldRevocation>;
}
