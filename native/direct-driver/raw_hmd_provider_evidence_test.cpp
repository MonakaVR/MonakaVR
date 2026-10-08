#include "MonakaRawHmdProviderEvidence.hpp"
#include <array>
#include <bit>
#include <iostream>
#include <latch>
#include <stdexcept>
#include <thread>
#include <type_traits>
#include <vector>

using namespace monaka;
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
namespace monaka {
struct RawHmdProviderEvidenceTestAccess {
    static void NearOverflow(RawHmdProviderEvidenceState& state) {
        state.next_ = static_cast<uint64_t>((std::numeric_limits<int64_t>::max)()) - 1;
    }
};
}
static_assert(!std::is_copy_constructible_v<RawHmdProviderEvidenceState>);
static_assert(!std::is_move_constructible_v<RawHmdProviderEvidenceState>);
static_assert(!std::is_copy_assignable_v<RawHmdProviderEvidenceSnapshot>);
static_assert(!std::is_constructible_v<RawHmdProviderEvidenceSnapshot, ProviderSessionEpoch,
    uint64_t, ProviderHmdPoseSample>);
static float f(uint32_t bits) { return std::bit_cast<float>(bits); }
static const ProviderPose raw{f(0), f(0x80000000), f(1), f(0x7f7fffff), f(0xff7fffff),
    f(0x7fc12345), f(0x7f800000)};
static const ProviderPose wire{f(0xff800000), f(0x7fc54321), f(0x80000001), f(0x3f800001),
    f(0x40000002), f(0xc0400003), f(0x80000000)};
static std::array<uint32_t, 7> bits(const ProviderPose& p) {
    return {std::bit_cast<uint32_t>(p.px), std::bit_cast<uint32_t>(p.py), std::bit_cast<uint32_t>(p.pz),
        std::bit_cast<uint32_t>(p.qx), std::bit_cast<uint32_t>(p.qy), std::bit_cast<uint32_t>(p.qz),
        std::bit_cast<uint32_t>(p.qw)};
}
static ProviderHmdPoseSample sample(bool valid = true, bool connected = true, int32_t result = 200) {
    return {raw, wire, 2, valid, connected, result};
}
static auto factory() {
    return [n = 0]() mutable { return "provider-test-" + std::to_string(n++); };
}
static void Lifecycle() {
    RawHmdProviderEvidenceState s(factory());
    require(!s.CurrentSession() && !s.Capture({"unissued"}, sample()), "inactive capture rejected");
    require(s.StartSession(), "start session");
    const auto a = *s.CurrentSession();
    std::optional<RawHmdProviderEvidenceSnapshot> retained;
    for (uint64_t id = 0; id != 3; ++id) {
        const auto value = s.Capture(a, sample());
        require(value && value->observationId == id && value->providerSession == a, "strict monotonic IDs from zero");
        require(s.CurrentSession() == a && s.CurrentSession() == a, "reads do not change session/counter");
        if (!retained) retained.emplace(*value);
    }
    s.RetireSession();
    require(!s.Capture(a, sample()) && !s.CurrentSession(), "retired cannot emit");
    require(s.StartSession(), "reconnect");
    const auto b = *s.CurrentSession();
    require(a != b && !s.Capture(a, sample()), "old sample ticket cannot cross reconnect");
    require(s.Capture(b, sample())->observationId == 0, "counter restart only with new session");
    require(retained->providerSession == a && retained->observationId == 0 &&
        bits(retained->sample.rawPose) == bits(raw), "retained immutable value survives retirement/new captures");
    require(s.StartSession() && *s.CurrentSession() != b, "explicit reestablishment retires active session");

    RawHmdProviderEvidenceState reuse([] { return "same"; });
    require(reuse.StartSession() && !reuse.StartSession() && !reuse.CurrentSession(), "reused token fails closed");
    RawHmdProviderEvidenceState blank([] { return " \t\r\n"; });
    require(!blank.StartSession() && !blank.CurrentSession(), "blank token fails closed");
    RawHmdProviderEvidenceState error([n = 0]() mutable -> std::string {
        if (n++) throw std::runtime_error("factory failed");
        return "first";
    });
    require(error.StartSession(), "start before factory failure");
    try { error.StartSession(); } catch (const std::runtime_error&) {}
    require(!error.CurrentSession() && !error.Capture({"first"}, sample()), "factory failure retires old token");
}
static void ExactFactsAndUnavailable() {
    RawHmdProviderEvidenceState s(factory());
    s.StartSession(); const auto session = *s.CurrentSession();
    uint64_t id = 0;
    for (bool valid : {false, true}) for (bool connected : {false, true})
        for (int32_t result : {INT32_MIN, -1, 0, 1, 100, 101, 200, 201, 300, INT32_MAX}) {
            const auto v = s.Capture(session, sample(valid, connected, result));
            require(v && v->observationId == id++, "one ID per snapshot");
            require(bits(v->sample.rawPose) == bits(raw) && bits(v->sample.wirePose) == bits(wire),
                "signed zero/subnormal/extremes/NaN payload/infinity preserved bit exactly");
            require(v->sample.dataSource == 2 && v->sample.poseValid == valid &&
                v->sample.deviceConnected == connected && v->sample.trackingResult == result,
                "raw validity facts copied without sourceValid synthesis");
            require(v->rawSpace.rawSpaceOwner == ProviderAuthorityStatus::Unavailable &&
                v->rawSpace.rawSpaceIncarnation == ProviderAuthorityStatus::Unavailable &&
                v->rawSpace.rawSpaceGeneration == ProviderAuthorityStatus::Unavailable &&
                v->mapping.outputSpaceEpoch == ProviderAuthorityStatus::Unavailable &&
                v->mapping.calibrationEpoch == ProviderAuthorityStatus::Unavailable &&
                v->mapping.mappingRevision == ProviderAuthorityStatus::Unavailable &&
                v->physicalAcquisitionTime == ProviderAuthorityStatus::Unavailable,
                "all unsupported authority explicitly unavailable");
        }
}
static void Overflow() {
    RawHmdProviderEvidenceState s(factory()); s.StartSession();
    const auto a = *s.CurrentSession();
    RawHmdProviderEvidenceTestAccess::NearOverflow(s);
    require(s.Capture(a, sample())->observationId == static_cast<uint64_t>((std::numeric_limits<int64_t>::max)()) - 1, "max minus one");
    const auto last = s.Capture(a, sample());
    require(last->observationId == static_cast<uint64_t>((std::numeric_limits<int64_t>::max)()) &&
        s.Lifecycle() == ProviderEvidenceLifecycle::EXHAUSTED && !s.CurrentSession(), "max retires immediately");
    require(!s.Capture(a, sample()) && !s.Capture(a, sample()), "no wrapping/reuse after exhaustion");
    require(s.StartSession() && *s.CurrentSession() != a, "fresh session required after overflow");
    require(s.Capture(*s.CurrentSession(), sample())->observationId == 0, "new counter after overflow");
}
int main() {
    try {
        Lifecycle(); ExactFactsAndUnavailable(); Overflow();
        std::cout << "PASS provider lifecycle, exact sample bits, validity matrix, overflow, immutable carrier; pure C++20 only\n";
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
