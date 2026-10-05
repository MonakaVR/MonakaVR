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

class MonakaHmdFrameProbe {
public:
    explicit MonakaHmdFrameProbe(std::string owner) : owner_(std::move(owner)) {}
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
        return Snapshot();
    }
    HmdFrameObservation ObserveSignal(FrameSignal signal) {
        // Events carry no new applied-transform sample. Retain the last sampled values explicitly.
        return Observe(previous_.value_or(FrameProbeObservation{}), signal);
    }
private:
    const std::string owner_; // Observer instance namespace, never a frame identity.
    uint64_t sequence_ = 0, generation_ = 0;
    std::optional<FrameProbeObservation> previous_;
    std::optional<uint64_t> lastKnownUniverse_;
    std::optional<AppliedUniverseTransform> lastAppliedTransform_;
    FrameSignal lastSignal_ = FrameSignal::PoseSample;
    FrameProofState proof_ = FrameProofState::Unknown;
    bool universeChanged_ = false, transformChanged_ = false, boundarySignal_ = false;
};
} // namespace monaka
