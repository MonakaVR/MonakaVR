#pragma once
#include <cmath>
#include <string_view>
#include "openvr_driver.h"
#include "ProtobufMessages.pb.h"

namespace monaka {
inline bool IsDirect(std::string_view serial) {
    return serial.starts_with("monaka-direct:resolved-v1:");
}

// OpenVR has one whole-pose validity flag, not an optional position vector.
// Fallback_RotationOnly explicitly carries live orientation with no valid 6DoF pose.
inline void Unavailable(vr::DriverPose_t& pose) {
    pose.poseIsValid = false;
    pose.deviceIsConnected = true; // lifetime/registration is independent of sample validity
    pose.result = vr::TrackingResult_Running_OutOfRange;
    pose.qRotation = {1, 0, 0, 0};
    for (int i = 0; i < 3; ++i) {
        // Storage only: never publish these coordinates as a valid origin pose.
        pose.vecPosition[i] = 0;
        pose.vecVelocity[i] = 0;
        pose.vecAcceleration[i] = 0;
        pose.vecAngularVelocity[i] = 0;
        pose.vecAngularAcceleration[i] = 0;
    }
}

inline void ApplyDirect(const messages::Position& p, vr::DriverPose_t& pose) {
    Unavailable(pose);
    if (!p.has_data_source() ||
        (p.data_source() != messages::Position::FULL && p.data_source() != messages::Position::IMU)) return;
    const double norm = double(p.qw()) * p.qw() + double(p.qx()) * p.qx() +
        double(p.qy()) * p.qy() + double(p.qz()) * p.qz();
    if (!std::isfinite(norm) || std::abs(norm - 1.0) > 0.01) return;
    if (p.data_source() == messages::Position::FULL) {
        if (!p.has_x() || !p.has_y() || !p.has_z() ||
            !std::isfinite(p.x()) || !std::isfinite(p.y()) || !std::isfinite(p.z())) return;
        pose.vecPosition[0] = p.x(); pose.vecPosition[1] = p.y(); pose.vecPosition[2] = p.z();
        pose.poseIsValid = true;
        pose.result = vr::TrackingResult_Running_OK;
    } else {
        if (p.has_x() || p.has_y() || p.has_z()) return;
        pose.result = vr::TrackingResult_Fallback_RotationOnly;
    }
    pose.qRotation = {p.qw(), p.qx(), p.qy(), p.qz()};
}
}
