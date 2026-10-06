#pragma once
#include "MonakaHmdFrameProbe.hpp"
#include "Logger.hpp"
#include <atomic>
#include <chrono>
#include <cstdlib>
#include <format>
#include <mutex>
#include <random>
#include <string_view>
#include <openvr_driver.h>

namespace monaka {
inline void CopyHmdTrackingObservation(FrameProbeObservation& input, const vr::TrackedDevicePose_t& pose) {
    input.trackingPoseValid = pose.bPoseIsValid;
    input.deviceConnected = pose.bDeviceIsConnected;
    input.trackingResult = static_cast<int32_t>(pose.eTrackingResult);
    // Deliberately exclude mDeviceToAbsoluteTracking, velocity and prediction time.
}
// Only constants present in OpenVR 91825305130f446f82054c1ec3d416321ace0072.
constexpr std::optional<FrameSignal> FrameSignalForOpenVrEvent(uint32_t event) {
    switch (event) {
    case vr::VREvent_ChaperoneUniverseHasChanged: return FrameSignal::ChaperoneUniverseChanged;
    case vr::VREvent_SeatedZeroPoseReset: return FrameSignal::SeatedZeroPoseReset;
    case vr::VREvent_StandingZeroPoseReset: return FrameSignal::StandingZeroPoseReset;
    // Partial semantics: notifications are recorded but do not imply an origin change.
    case vr::VREvent_ChaperoneDataHasChanged: return FrameSignal::ChaperoneDataChanged;
    case vr::VREvent_ChaperoneTempDataHasChanged: return FrameSignal::ChaperoneTempDataChanged;
    case vr::VREvent_ChaperoneSettingsHaveChanged: return FrameSignal::ChaperoneSettingsChanged;
    case vr::VREvent_ChaperoneFlushCache: return FrameSignal::ChaperoneFlushCache;
    case vr::VREvent_ChaperoneRoomSetupStarting: return FrameSignal::RoomSetupStarting;
    case vr::VREvent_ChaperoneRoomSetupCommitted: return FrameSignal::RoomSetupCommitted;
    default: return std::nullopt;
    }
}
inline const char* SignalName(FrameSignal value) {
    switch (value) {
    case FrameSignal::PoseSample: return "pose_sample";
    case FrameSignal::ChaperoneUniverseChanged: return "chaperone_universe_changed";
    case FrameSignal::SeatedZeroPoseReset: return "seated_zero_pose_reset";
    case FrameSignal::StandingZeroPoseReset: return "standing_zero_pose_reset";
    case FrameSignal::ChaperoneDataChanged: return "chaperone_data_changed";
    case FrameSignal::ChaperoneTempDataChanged: return "chaperone_temp_data_changed";
    case FrameSignal::ChaperoneSettingsChanged: return "chaperone_settings_changed";
    case FrameSignal::ChaperoneFlushCache: return "chaperone_flush_cache";
    case FrameSignal::RoomSetupStarting: return "room_setup_starting";
    case FrameSignal::RoomSetupCommitted: return "room_setup_committed";
    }
    return "unknown";
}
inline const char* ProofName(FrameProofState value) {
    switch (value) {
    case FrameProofState::Unknown: return "UNKNOWN";
    case FrameProofState::ObservedStableInputs: return "OBSERVED_STABLE_INPUTS";
    case FrameProofState::BoundarySignalObserved: return "BOUNDARY_SIGNAL_OBSERVED";
    case FrameProofState::UniverseChanged: return "UNIVERSE_CHANGED";
    case FrameProofState::TransformChanged: return "TRANSFORM_CHANGED";
    case FrameProofState::TransformLookupFailed: return "TRANSFORM_LOOKUP_FAILED";
    case FrameProofState::InvalidTransform: return "INVALID_TRANSFORM";
    }
    return "UNKNOWN";
}
inline const char* LookupName(TransformLookupState value) {
    switch (value) {
    case TransformLookupState::Unknown: return "unknown";
    case TransformLookupState::CachedForObservedUniverse: return "cached_not_refreshed";
    case TransformLookupState::RefreshSucceeded: return "refresh_succeeded";
    case TransformLookupState::RefreshFailed: return "refresh_failed";
    case TransformLookupState::UniverseUnavailable: return "universe_unavailable";
    }
    return "unknown";
}
inline bool ProbeEnvironmentEnabled(const char* name) {
    const char* value = std::getenv(name);
    return value && std::string_view(value) == "1";
}
inline std::string NewProbeOwner() {
    // Created once, only when enabled. Random instance namespace plus in-process uniqueness.
    static std::atomic<uint64_t> instances{0};
    std::random_device random;
    return std::format("{:08x}{:08x}{:08x}{:08x}-{}", random(), random(), random(), random(), ++instances);
}
inline std::string ProbeId(std::optional<uint64_t> id) {
    return id ? std::to_string(*id) : "unavailable";
}
inline std::string ProbeTransform(std::optional<AppliedUniverseTransform> transform) {
    if (!transform) return "unavailable";
    const auto& t = *transform;
    // Bits make signed zero, NaNs and exact binary32 changes machine-readable.
    return std::format("{:08x},{:08x},{:08x},{:08x}", std::bit_cast<uint32_t>(t.tx),
        std::bit_cast<uint32_t>(t.ty), std::bit_cast<uint32_t>(t.tz), std::bit_cast<uint32_t>(t.yaw));
}
inline std::string FormatProbeDiagnostic(const HmdFrameObservation& s, bool poseAssociation) {
    return std::format("MONAKA_FRAME_PROBE_V1 kind={} owner={} seq={} boundary={} signal={} proof={} "
        "universe={} applied_cache_universe={} applied_f32_bits={} lookup={} tracking_valid={} connected={} "
        "tracking_result={} universe_changed={} transform_changed={} boundary_signal={} coverage_incomplete=1 "
        "value_context={} poseProbeObservationSequence={}",
        poseAssociation ? "pose_association" : "state", s.observerOwner, s.observationSequence,
        s.detectedBoundaryGeneration, SignalName(s.lastSignal), ProofName(s.proofState),
        ProbeId(s.values.universeId), ProbeId(s.values.appliedCacheUniverseId),
        ProbeTransform(s.values.appliedTransform), LookupName(s.values.lookup), s.values.trackingPoseValid,
        s.values.deviceConnected, s.values.trackingResult, s.universeChanged, s.transformChanged,
        s.boundarySignalObserved, s.lastSignal == FrameSignal::PoseSample ? "same_raw_pose_sample" : "last_pose_sample",
        poseAssociation ? std::to_string(s.observationSequence) : "unavailable");
}

inline const char* BindingAssessmentName(PoseBindingAssessment value) {
    switch (value) {
    case PoseBindingAssessment::NO_OBSERVED_BOUNDARY_SINCE_CAPTURE: return "NO_OBSERVED_BOUNDARY_SINCE_CAPTURE";
    case PoseBindingAssessment::OBSERVED_BOUNDARY_SINCE_CAPTURE: return "OBSERVED_BOUNDARY_SINCE_CAPTURE";
    case PoseBindingAssessment::CAPTURE_OWNER_MISMATCH: return "CAPTURE_OWNER_MISMATCH";
    case PoseBindingAssessment::FRAME_OBSERVATION_UNAVAILABLE: return "FRAME_OBSERVATION_UNAVAILABLE";
    case PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE: return "CAPTURE_MARKER_REUSED_OR_STALE";
    }
    return "FRAME_OBSERVATION_UNAVAILABLE";
}
inline std::string PosePositionBits(const HmdDiagnosticPose& p) {
    return std::format("{:08x},{:08x},{:08x}", std::bit_cast<uint32_t>(p.px),
        std::bit_cast<uint32_t>(p.py), std::bit_cast<uint32_t>(p.pz));
}
inline std::string PoseQuaternionBits(const HmdDiagnosticPose& p) {
    return std::format("{:08x},{:08x},{:08x},{:08x}", std::bit_cast<uint32_t>(p.qx),
        std::bit_cast<uint32_t>(p.qy), std::bit_cast<uint32_t>(p.qz), std::bit_cast<uint32_t>(p.qw));
}
inline std::string FormatBindingDiagnostic(const HmdObservedPoseFrameBinding& b) {
    const auto& s = b.bound;
    return std::format("MONAKA_FRAME_PROBE_V1 kind=pose_binding owner={} capture_owner={} capture_ordinal={} "
        "capture_observation_seq={} capture_boundary={} bind_observation_seq={} bind_boundary={} "
        "owner_match={} capture_usable={} observed_boundary_since_capture={} boundary_during_bind={} "
        "binding_assessment={} signal={} proof={} universe={} applied_cache_universe={} applied_f32_bits={} "
        "lookup={} tracking_valid={} connected={} tracking_result={} universe_changed={} transform_changed={} "
        "boundary_signal={} raw_pos_f32_bits={} raw_q_f32_bits={} wire_pos_f32_bits={} wire_q_f32_bits={} "
        "data_source={} interval_last_boundary_seq={} interval_last_boundary_signal={} "
        "interval_last_universe_changed={} interval_last_transform_changed={} interval_last_boundary_signal_observed={} coverage_incomplete=1",
        s.observerOwner, b.capture.observerOwner, b.capture.captureOrdinal, b.capture.observationSequence,
        b.capture.detectedBoundaryGeneration, s.observationSequence, s.detectedBoundaryGeneration,
        b.ownerMatches, b.captureUsable,
        b.observedBoundarySinceCapture ? (*b.observedBoundarySinceCapture ? "true" : "false") : "unavailable",
        b.boundaryDuringBind, BindingAssessmentName(b.assessment), SignalName(s.lastSignal), ProofName(s.proofState),
        ProbeId(s.values.universeId), ProbeId(s.values.appliedCacheUniverseId), ProbeTransform(s.values.appliedTransform),
        LookupName(s.values.lookup), s.values.trackingPoseValid, s.values.deviceConnected, s.values.trackingResult,
        s.universeChanged, s.transformChanged, s.boundarySignalObserved, PosePositionBits(b.rawPose),
        PoseQuaternionBits(b.rawPose), PosePositionBits(b.wirePose), PoseQuaternionBits(b.wirePose), b.dataSource,
        b.lastObservedBoundarySinceCapture ? std::to_string(b.lastObservedBoundarySinceCapture->observationSequence) : "unavailable",
        b.lastObservedBoundarySinceCapture ? SignalName(b.lastObservedBoundarySinceCapture->lastSignal) : "unavailable",
        b.lastObservedBoundarySinceCapture ? (b.lastObservedBoundarySinceCapture->universeChanged ? "true" : "false") : "unavailable",
        b.lastObservedBoundarySinceCapture ? (b.lastObservedBoundarySinceCapture->transformChanged ? "true" : "false") : "unavailable",
        b.lastObservedBoundarySinceCapture ? (b.lastObservedBoundarySinceCapture->boundarySignalObserved ? "true" : "false") : "unavailable");
}

// The observer and state logger are serialized across RunFrame and the pose thread.
// No queue, second event pump, runtime query, file I/O or transport callback is added.
class HmdFrameProbeDiagnostics {
public:
    explicit HmdFrameProbeDiagnostics(std::string owner, bool poseLogs, bool bindingLogs = false)
        : probe_(std::move(owner)), poseLogs_(poseLogs), bindingLogs_(bindingLogs) {}
    HmdRawPoseCaptureMarker CaptureRawPose() {
        std::lock_guard<std::mutex> lock(mutex_);
        return probe_.CaptureRawPose();
    }
    // Callback is the original send invocation. It must not re-enter this adapter.
    // The pinned bridge queues bytes/signals its worker; it never waits for RunFrame.
    template<class Send>
    HmdObservedPoseFrameBinding BindAndSend(const HmdRawPoseCaptureMarker& capture,
        const FrameProbeObservation& input, const HmdDiagnosticPose& raw,
        const HmdDiagnosticPose& wire, int32_t dataSource, Logger& logger, Send&& send) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto binding = probe_.BindPose(capture, input, raw, wire, dataSource);
        LogPose(binding.bound, true, logger);
        if (bindingLogs_) logger.Log("{}", FormatBindingDiagnostic(binding));
        std::forward<Send>(send)(); // Exactly once even if diagnostic assessment is unavailable/mismatched.
        return binding;
    }
    void ObserveEvent(uint32_t event, Logger& logger) {
        const auto signal = FrameSignalForOpenVrEvent(event);
        if (!signal) return;
        std::lock_guard<std::mutex> lock(mutex_);
        LogState(probe_.ObserveSignal(*signal), logger, true);
    }
    void ObservePose(const FrameProbeObservation& input, bool sendingPosition, Logger& logger) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto snapshot = probe_.Observe(input);
        LogPose(snapshot, sendingPosition, logger);
    }
private:
    // Allows a standalone test to prove failed lock acquisition without timing/sleeps.
    friend struct HmdFrameProbeDiagnosticsTestAccess;
    void LogPose(const HmdFrameObservation& snapshot, bool sendingPosition, Logger& logger) {
        LogState(snapshot, logger, false);
        if (poseLogs_ && sendingPosition) {
            const auto now = std::chrono::steady_clock::now();
            if (!lastPoseLog_ || now - *lastPoseLog_ >= std::chrono::seconds(1)) {
                logger.Log("{}", FormatProbeDiagnostic(snapshot, true));
                lastPoseLog_ = now; // Log-rate limiting only; not a physical acquisition timestamp.
            }
        }
    }
    void LogState(const HmdFrameObservation& s, Logger& logger, bool event) {
        if (event || !lastLogged_ || s.detectedBoundaryGeneration != lastLogged_->detectedBoundaryGeneration ||
            s.proofState != lastLogged_->proofState || s.values.lookup != lastLogged_->values.lookup ||
            s.values.trackingPoseValid != lastLogged_->values.trackingPoseValid ||
            s.values.deviceConnected != lastLogged_->values.deviceConnected ||
            s.values.trackingResult != lastLogged_->values.trackingResult ||
            s.values.universeId != lastLogged_->values.universeId ||
            s.values.appliedCacheUniverseId != lastLogged_->values.appliedCacheUniverseId ||
            s.values.appliedTransform != lastLogged_->values.appliedTransform) {
            logger.Log("{}", FormatProbeDiagnostic(s, false));
            lastLogged_.emplace(s);
        }
    }
    std::mutex mutex_;
    MonakaHmdFrameProbe probe_;
    const bool poseLogs_;
    const bool bindingLogs_;
    std::optional<HmdFrameObservation> lastLogged_;
    std::optional<std::chrono::steady_clock::time_point> lastPoseLog_;
};
} // namespace monaka
