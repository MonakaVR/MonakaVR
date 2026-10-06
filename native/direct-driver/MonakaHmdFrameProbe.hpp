#pragma once
#include <bit>
#include <cmath>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>

namespace monaka {
// These are the float values used by the driver send path, NOT a live standing-frame query.
struct AppliedUniverseTransform {
    float tx, ty, tz, yaw;
    bool IsFinite() const {
        return std::isfinite(tx) && std::isfinite(ty) && std::isfinite(tz) && std::isfinite(yaw);
    }
    bool operator==(const AppliedUniverseTransform& other) const {
        // Exact binary32 content; +0 and -0 differ. No epsilon, NaN normalization or hash identity.
        return std::bit_cast<uint32_t>(tx) == std::bit_cast<uint32_t>(other.tx) &&
            std::bit_cast<uint32_t>(ty) == std::bit_cast<uint32_t>(other.ty) &&
            std::bit_cast<uint32_t>(tz) == std::bit_cast<uint32_t>(other.tz) &&
            std::bit_cast<uint32_t>(yaw) == std::bit_cast<uint32_t>(other.yaw);
    }
};

enum class FrameSignal {
    PoseSample, ChaperoneUniverseChanged, SeatedZeroPoseReset, StandingZeroPoseReset,
    ChaperoneDataChanged, ChaperoneTempDataChanged, ChaperoneSettingsChanged,
    ChaperoneFlushCache, RoomSetupStarting, RoomSetupCommitted
};
constexpr bool IsDetectedBoundarySignal(FrameSignal signal) {
    return signal == FrameSignal::ChaperoneUniverseChanged ||
        signal == FrameSignal::SeatedZeroPoseReset || signal == FrameSignal::StandingZeroPoseReset;
}
enum class TransformLookupState { Unknown, CachedForObservedUniverse, RefreshSucceeded, RefreshFailed, UniverseUnavailable };
enum class FrameProofState {
    Unknown, ObservedStableInputs, BoundarySignalObserved, UniverseChanged, TransformChanged,
    TransformLookupFailed, InvalidTransform
};

// Copied values only. Pose coordinates/orientation, transport sessions and Tracker objects are absent.
struct FrameProbeObservation {
    std::optional<uint64_t> universeId;
    std::optional<uint64_t> appliedCacheUniverseId;
    std::optional<AppliedUniverseTransform> appliedTransform;
    TransformLookupState lookup = TransformLookupState::Unknown;
    bool trackingPoseValid = false;
    bool deviceConnected = false;
    int32_t trackingResult = 0;
};

// Immutable value snapshot; no references to mutable driver state.
struct HmdFrameObservation {
    const std::string observerOwner;
    const uint64_t observationSequence;
    const uint64_t detectedBoundaryGeneration;
    const FrameProbeObservation values;
    const FrameSignal lastSignal;
    const FrameProofState proofState;
    const bool universeChanged;
    const bool transformChanged;
    const bool boundarySignalObserved;
    // Always true in this phase, including ObservedStableInputs.
    const bool coverageIncomplete = true;
};

// Software observation point after the raw query returns; NOT acquisition time or frame identity.
struct HmdRawPoseCaptureMarker {
    const std::string observerOwner;
    const uint64_t captureOrdinal;
    const uint64_t observationSequence;
    const uint64_t detectedBoundaryGeneration;
};
struct HmdDiagnosticPose {
    const float px, py, pz, qx, qy, qz, qw;
};
enum class PoseBindingAssessment {
    NO_OBSERVED_BOUNDARY_SINCE_CAPTURE, OBSERVED_BOUNDARY_SINCE_CAPTURE,
    CAPTURE_OWNER_MISMATCH, FRAME_OBSERVATION_UNAVAILABLE, CAPTURE_MARKER_REUSED_OR_STALE
};
struct HmdObservedPoseFrameBinding {
    const HmdRawPoseCaptureMarker capture;
    const HmdFrameObservation bound;
    const bool ownerMatches;
    const bool captureUsable;
    // Unavailable on owner mismatch or stale/reused marker: no cross-owner comparison claim.
    const std::optional<bool> observedBoundarySinceCapture;
    const bool boundaryDuringBind;
    // Most recent observed boundary in this interval, not an exhaustive event history.
    const std::optional<HmdFrameObservation> lastObservedBoundarySinceCapture;
    const HmdDiagnosticPose rawPose;
    const HmdDiagnosticPose wirePose;
    const int32_t dataSource;
    const PoseBindingAssessment assessment;
};

class MonakaHmdFrameProbe {
public:
    explicit MonakaHmdFrameProbe(std::string owner) : owner_(std::move(owner)) {}
    HmdRawPoseCaptureMarker CaptureRawPose() {
        captureConsumed_ = false;
        captureSequence_ = sequence_;
        captureGeneration_ = generation_;
        return {owner_, ++captureOrdinal_, sequence_, generation_};
    }
    HmdObservedPoseFrameBinding BindPose(const HmdRawPoseCaptureMarker& capture,
        const FrameProbeObservation& input, const HmdDiagnosticPose& raw,
        const HmdDiagnosticPose& wire, int32_t dataSource) {
        const bool ownerMatches = capture.observerOwner == owner_;
        const bool usable = ownerMatches && !captureConsumed_ && capture.captureOrdinal == captureOrdinal_ &&
            capture.captureOrdinal != 0 && capture.observationSequence == captureSequence_ &&
            capture.detectedBoundaryGeneration == captureGeneration_;
        const auto before = generation_;
        const auto bound = Observe(input);
        const std::optional<bool> changed = usable
            ? std::optional<bool>(bound.detectedBoundaryGeneration != capture.detectedBoundaryGeneration)
            : std::nullopt;
        auto assessment = PoseBindingAssessment::NO_OBSERVED_BOUNDARY_SINCE_CAPTURE;
        if (!ownerMatches) assessment = PoseBindingAssessment::CAPTURE_OWNER_MISMATCH;
        else if (!usable) assessment = PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE;
        else if (*changed) assessment = PoseBindingAssessment::OBSERVED_BOUNDARY_SINCE_CAPTURE;
        else if (bound.proofState == FrameProofState::Unknown ||
                 bound.proofState == FrameProofState::TransformLookupFailed ||
                 bound.proofState == FrameProofState::InvalidTransform)
            assessment = PoseBindingAssessment::FRAME_OBSERVATION_UNAVAILABLE;
        if (usable) captureConsumed_ = true;
        return {capture, bound, ownerMatches, usable, changed, before != generation_,
            usable && *changed ? lastBoundary_ : std::nullopt, raw, wire, dataSource, assessment};
    }
    HmdFrameObservation Snapshot() const {
        return {owner_, sequence_, generation_, previous_.value_or(FrameProbeObservation{}),
                lastSignal_, proof_, universeChanged_, transformChanged_, boundarySignal_};
    }
    HmdFrameObservation Observe(const FrameProbeObservation& input, FrameSignal signal = FrameSignal::PoseSample) {
        ++sequence_;
        // Preserve the last known ID/value through unavailable inputs, without presenting it as current.
        universeChanged_ = input.universeId && lastKnownUniverse_ && input.universeId != lastKnownUniverse_;
        transformChanged_ = input.appliedTransform && lastAppliedTransform_ &&
            !(*input.appliedTransform == *lastAppliedTransform_);
        boundarySignal_ = IsDetectedBoundarySignal(signal);
        // One generation per input, even if the input contains several observed reasons.
        if (universeChanged_ || transformChanged_ || boundarySignal_) ++generation_;
        const bool repeated = previous_ && previous_->universeId == input.universeId &&
            previous_->appliedCacheUniverseId == input.appliedCacheUniverseId &&
            previous_->appliedTransform == input.appliedTransform;
        if (input.appliedTransform && !input.appliedTransform->IsFinite()) {
            proof_ = FrameProofState::InvalidTransform;
        } else if (input.lookup == TransformLookupState::RefreshFailed) {
            proof_ = FrameProofState::TransformLookupFailed;
        } else if (!input.universeId || !input.appliedTransform ||
                   input.appliedCacheUniverseId != input.universeId ||
                   input.lookup == TransformLookupState::Unknown ||
                   input.lookup == TransformLookupState::UniverseUnavailable ||
                   !input.trackingPoseValid || !input.deviceConnected) {
            proof_ = FrameProofState::Unknown;
        } else if (boundarySignal_) {
            proof_ = FrameProofState::BoundarySignalObserved;
        } else if (universeChanged_) {
            proof_ = FrameProofState::UniverseChanged;
        } else if (transformChanged_) {
            proof_ = FrameProofState::TransformChanged;
        } else {
            proof_ = repeated ? FrameProofState::ObservedStableInputs : FrameProofState::Unknown;
        }
        if (input.universeId) lastKnownUniverse_ = input.universeId;
        if (input.appliedTransform) lastAppliedTransform_ = input.appliedTransform;
        previous_ = input;
        lastSignal_ = signal;
        const auto snapshot = Snapshot();
        if (universeChanged_ || transformChanged_ || boundarySignal_) lastBoundary_.emplace(snapshot);
        return snapshot;
    }
    HmdFrameObservation ObserveSignal(FrameSignal signal) {
        // Events carry no new applied-transform sample. Retain the last sampled values explicitly.
        return Observe(previous_.value_or(FrameProbeObservation{}), signal);
    }
private:
    const std::string owner_; // Observer instance namespace, never a frame identity.
    uint64_t sequence_ = 0, generation_ = 0;
    uint64_t captureOrdinal_ = 0, captureSequence_ = 0, captureGeneration_ = 0;
    bool captureConsumed_ = true;
    std::optional<FrameProbeObservation> previous_;
    std::optional<HmdFrameObservation> lastBoundary_;
    std::optional<uint64_t> lastKnownUniverse_;
    std::optional<AppliedUniverseTransform> lastAppliedTransform_;
    FrameSignal lastSignal_ = FrameSignal::PoseSample;
    FrameProofState proof_ = FrameProofState::Unknown;
    bool universeChanged_ = false, transformChanged_ = false, boundarySignal_ = false;
};
} // namespace monaka
