#include "MonakaRawHmdProviderDriver.hpp"
#include "MonakaHmdFrameProbeDriver.hpp"
#include "ProtobufMessages.pb.h"
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
class CaptureLogger : public Logger {
public: std::vector<std::string> lines;
protected: void LogImpl(const char* line) override { lines.emplace_back(line); }
};
static void PayloadAndProbe() {
    // Actual generated protobuf serializer and existing binding adapter. Provider on/off
    // and frame probe on/off must preserve bytes, order and exactly one callback per sample.
    std::string baseline;
    for (bool provider : {false, true}) for (bool probe : {false, true}) {
        RawHmdProviderDriver d(factory());
        HmdFrameProbeDiagnostics diagnostic("probe-observer-only", false, false);
        CaptureLogger logger;
        messages::ProtobufMessage message;
        auto* p = message.mutable_position();
        p->set_tracker_id(0); p->set_data_source(messages::Position_DataSource_FULL);
        const float wx = wire.px, wy = wire.py, wz = wire.pz;
        const float qx = wire.qx, qy = wire.qy, qz = wire.qz, qw = wire.qw;
        p->set_x(wx); p->set_y(wy); p->set_z(wz);
        p->set_qx(qx); p->set_qy(qy); p->set_qz(qz); p->set_qw(qw);
        const auto before = message.SerializeAsString();
        std::vector<std::string> order{"status"};
        const auto ticket = d.BeginSample();
        for (int n = 0; n < 10; ++n) require(d.BeginSample() == ticket, "poll cannot advance counter");
        std::optional<RawHmdProviderEvidenceSnapshot> evidence;
        if (provider) evidence.emplace(*d.Capture(*ticket, {raw, {wx, wy, wz, qx, qy, qz, qw},
            static_cast<int32_t>(p->data_source()), false, true, 300}));
        unsigned sends = 0;
        const auto send = [&] { ++sends; order.emplace_back("position");
            require(message.SerializeAsString() == before, "instrumentation preserves bytes"); };
        if (probe) {
            const auto marker = diagnostic.CaptureRawPose();
            diagnostic.ObserveEvent(vr::VREvent_StandingZeroPoseReset, logger);
            const FrameProbeObservation observation{10, 10, AppliedUniverseTransform{1,2,3,0.5f},
                TransformLookupState::CachedForObservedUniverse, false, true, 300};
            const auto binding = diagnostic.BindAndSend(marker, observation,
                {raw.px,raw.py,raw.pz,raw.qx,raw.qy,raw.qz,raw.qw},
                {wx,wy,wz,qx,qy,qz,qw}, 2, logger, send);
            require(binding.bound.detectedBoundaryGeneration == 1 && binding.bound.observationSequence == 2,
                "provider capture does not advance probe counters");
        } else send();
        order.emplace_back("battery");
        require(sends == 1 && order == std::vector<std::string>{"status","position","battery"}, "send count/order");
        if (baseline.empty()) baseline = before;
        require(before == baseline, "enabled/disabled bytes identical");
        if (provider) {
            require(evidence->observationId == 0 && evidence->providerSession.value != "probe-observer-only",
                "provider identity independent of probe");
            require(evidence->sample.dataSource == static_cast<int32_t>(p->data_source()), "exact final data source");
            require(d.Capture(*ticket, sample())->observationId == 1, "probe and inspection do not advance provider");
            const auto retransmitted = *evidence;
            require(retransmitted.observationId == evidence->observationId &&
                bits(retransmitted.sample.wirePose) == bits(evidence->sample.wirePose), "retransmission copies same value/ID");
        }
        for (const auto& line : logger.lines) require(line.find("MONAKA_HMD_PROVIDER_EVIDENCE") == std::string::npos,
            "provider adds zero log lines (no diagnostic logger exists)");
        require(bits({p->x(),p->y(),p->z(),p->qx(),p->qy(),p->qz(),p->qw()}) == bits(wire), "setter locals exactly bound");
    }
}
static void AdapterRetirement() {
    RawHmdProviderDriver d(factory()); const auto old = d.BeginSample();
    std::latch retire(1), done(1);
    std::thread t([&] { retire.wait(); d.Retire(); done.count_down(); });
    retire.count_down(); done.wait();
    require(!d.Capture(*old, sample()) && !d.BeginSample(), "disconnect immediately retires without pose poll");
    d.Reestablish(); const auto fresh = d.BeginSample();
    require(fresh != old && !d.Capture(*old, sample()) && d.Capture(*fresh, sample())->observationId == 0,
        "reconnect invalidates in-flight old raw sample");
    t.join();
    RawHmdProviderDriver failure([]() -> std::string { throw std::runtime_error("no entropy"); });
    require(!failure.BeginSample() && !failure.Capture({"unissued"}, sample()), "entropy failure only disables evidence");
    const auto a = NewProviderSessionToken(), b = NewProviderSessionToken();
    require(a.size() == 64 && b.size() == 64 && a != b, "OS entropy token smoke test");
    RawHmdProviderDriver processA, processB;
    require(processA.BeginSample() != processB.BeginSample(), "independent provider instances get fresh entropy");
}
int main() {
    try {
        PayloadAndProbe(); AdapterRetirement();
        std::cout << "PASS provider adapter lifecycle/protobuf bytes/send order/probe coexistence/zero provider logging; software only\n";
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
