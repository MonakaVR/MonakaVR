#pragma once
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <variant>
#include <vector>
namespace monaka::protocol::v1 {
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
    std::string device_id{};
    std::string session_id{};
    std::int64_t sequence{};
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
    std::optional<Battery> battery{};
    std::uint32_t mapping_revision{};
};
using Envelope = std::variant<TrackerObservation,ObservationDeviceState,MtpPose,MtpTrackerState>;
}
