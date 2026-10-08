#pragma once
#include <cstdint>
#include <functional>
#include <limits>
#include <optional>
#include <string>
#include <unordered_set>
#include <utility>

namespace monaka {
struct ProviderSessionEpoch {
    const std::string value;
    bool operator==(const ProviderSessionEpoch&) const = default;
};
// Binary32 copies from existing conversion/send locals. Preserve even nonfinite bits;
// this carrier neither normalizes poses nor decides a Strong Trusted sourceValid policy.
struct ProviderPose {
    const float px, py, pz, qx, qy, qz, qw;
};
struct ProviderHmdPoseSample {
    const ProviderPose rawPose, wirePose;
    const int32_t dataSource;
    const bool poseValid, deviceConnected;
    const int32_t trackingResult;
};
enum class ProviderAuthorityStatus { Unavailable };
enum class ProviderUnavailableReason { MUTATION_COVERAGE_UNPROVEN, PHYSICAL_TIME_NOT_PROVIDED };
struct RawSpaceAuthority {
    const ProviderAuthorityStatus rawSpaceOwner = ProviderAuthorityStatus::Unavailable;
    const ProviderAuthorityStatus rawSpaceIncarnation = ProviderAuthorityStatus::Unavailable;
    const ProviderAuthorityStatus rawSpaceGeneration = ProviderAuthorityStatus::Unavailable;
    const ProviderUnavailableReason reason = ProviderUnavailableReason::MUTATION_COVERAGE_UNPROVEN;
};
struct MappingAuthority {
    const ProviderAuthorityStatus outputSpaceEpoch = ProviderAuthorityStatus::Unavailable;
    const ProviderAuthorityStatus calibrationEpoch = ProviderAuthorityStatus::Unavailable;
    const ProviderAuthorityStatus mappingRevision = ProviderAuthorityStatus::Unavailable;
    const ProviderUnavailableReason reason = ProviderUnavailableReason::MUTATION_COVERAGE_UNPROVEN;
};
// Describes source code conventions only, never a reviewed space identity/epoch.
enum class ProviderNumericMapping { RH_Y_UP_NEG_Z_FORWARD_METERS_HAMILTON_XYZW_UNIVERSE_CONVERSION };
class RawHmdProviderEvidenceSnapshot {
public:
    const ProviderSessionEpoch providerSession;
    const uint64_t observationId;
    const ProviderHmdPoseSample sample;
    const RawSpaceAuthority rawSpace{};
    const MappingAuthority mapping{};
    const ProviderNumericMapping outputDescriptor =
        ProviderNumericMapping::RH_Y_UP_NEG_Z_FORWARD_METERS_HAMILTON_XYZW_UNIVERSE_CONVERSION;
    const ProviderAuthorityStatus physicalAcquisitionTime = ProviderAuthorityStatus::Unavailable;
    const ProviderUnavailableReason physicalTimeReason = ProviderUnavailableReason::PHYSICAL_TIME_NOT_PROVIDED;
private:
    friend class RawHmdProviderEvidenceState;
    RawHmdProviderEvidenceSnapshot(ProviderSessionEpoch session, uint64_t id, ProviderHmdPoseSample value)
        : providerSession(std::move(session)), observationId(id), sample(value) {}
};
enum class ProviderEvidenceLifecycle { INACTIVE, ACTIVE, EXHAUSTED };

// Single-owner pure state. Adapters serialize lifecycle and capture; callers cannot
// supply IDs or construct differently-valued snapshots with an already issued ID.
class RawHmdProviderEvidenceState {
public:
    using TokenFactory = std::function<std::string()>;
    explicit RawHmdProviderEvidenceState(TokenFactory factory) : factory_(std::move(factory)) {}
    RawHmdProviderEvidenceState(const RawHmdProviderEvidenceState&) = delete;
    RawHmdProviderEvidenceState& operator=(const RawHmdProviderEvidenceState&) = delete;
    RawHmdProviderEvidenceState(RawHmdProviderEvidenceState&&) = delete;
    RawHmdProviderEvidenceState& operator=(RawHmdProviderEvidenceState&&) = delete;
    bool StartSession() {
        RetireSession(); // Even factory failure cannot leave the old session active.
        const auto token = factory_();
        if (token.find_first_not_of(" \t\r\n\f\v") == std::string::npos || !issued_.insert(token).second)
            return false;
        session_.emplace(ProviderSessionEpoch{token});
        next_ = 0;
        lifecycle_ = ProviderEvidenceLifecycle::ACTIVE;
        return true;
    }
    void RetireSession() {
        session_.reset();
        lifecycle_ = ProviderEvidenceLifecycle::INACTIVE;
    }
    std::optional<ProviderSessionEpoch> CurrentSession() const { return session_; }
    ProviderEvidenceLifecycle Lifecycle() const { return lifecycle_; }
    std::optional<RawHmdProviderEvidenceSnapshot> Capture(const ProviderSessionEpoch& expected,
        const ProviderHmdPoseSample& sample) {
        if (lifecycle_ != ProviderEvidenceLifecycle::ACTIVE || !session_ || *session_ != expected)
            return std::nullopt;
        const RawHmdProviderEvidenceSnapshot snapshot{*session_, next_, sample};
        // Keep issuance in the wire/server nonnegative Kotlin Long domain.
        if (next_ == static_cast<uint64_t>((std::numeric_limits<int64_t>::max)())) {
            session_.reset();
            lifecycle_ = ProviderEvidenceLifecycle::EXHAUSTED;
        } else {
            ++next_;
        }
        return snapshot;
    }
private:
    friend struct RawHmdProviderEvidenceTestAccess; // Counter injection only in standalone tests.
    const TokenFactory factory_;
    std::unordered_set<std::string> issued_;
    std::optional<ProviderSessionEpoch> session_;
    uint64_t next_ = 0;
    ProviderEvidenceLifecycle lifecycle_ = ProviderEvidenceLifecycle::INACTIVE;
};
} // namespace monaka
