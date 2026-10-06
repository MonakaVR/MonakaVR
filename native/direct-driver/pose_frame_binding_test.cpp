#include "MonakaHmdFrameProbeDriver.hpp"
#include "ProtobufMessages.pb.h"
#include <array>
#include <iostream>
#include <latch>
#include <limits>
#include <stdexcept>
#include <sstream>
#include <set>
#include <thread>
#include <type_traits>
#include <vector>

using namespace monaka;
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
static FrameProbeObservation Sample(uint64_t universe = 10) {
    return {universe, universe, AppliedUniverseTransform{1, 2, 3, 0.5f},
        TransformLookupState::CachedForObservedUniverse, true, true, vr::TrackingResult_Running_OK};
}
static float Float(uint32_t bits) { return std::bit_cast<float>(bits); }
static const HmdDiagnosticPose raw{Float(0x80000000), Float(0x00000001), Float(0x7f800000),
    Float(0x3f800001), Float(0x40000002), Float(0xc0400003), Float(0x7fc12345)};
static const HmdDiagnosticPose wire{Float(0x3e800004), Float(0xbe800005), Float(0x00000006),
    Float(0x3f000007), Float(0xbf000008), Float(0x3f400009), Float(0x3f60000a)};
static_assert(!std::is_copy_assignable_v<HmdRawPoseCaptureMarker>);
static_assert(!std::is_copy_assignable_v<HmdDiagnosticPose>);
static_assert(!std::is_copy_assignable_v<HmdObservedPoseFrameBinding>);

class CaptureLogger : public Logger {
public:
    std::vector<std::string> lines;
protected:
    void LogImpl(const char* line) override { lines.emplace_back(line); }
};
namespace monaka {
struct HmdFrameProbeDiagnosticsTestAccess {
    static bool TryLock(HmdFrameProbeDiagnostics& d) {
        if (!d.mutex_.try_lock()) return false;
        d.mutex_.unlock();
        return true;
    }
};
}

static void CoreMatrix() {
    MonakaHmdFrameProbe probe("A");
    const auto initial = probe.CaptureRawPose();
    require(initial.captureOrdinal == 1 && initial.observationSequence == 0 &&
        initial.detectedBoundaryGeneration == 0, "initial marker copies state without Observe/query");
    auto first = probe.BindPose(initial, Sample(), raw, wire, 2);
    require(first.assessment == PoseBindingAssessment::FRAME_OBSERVATION_UNAVAILABLE &&
        first.observedBoundarySinceCapture == false && !first.boundaryDuringBind,
        "first pose remains unknown; equality is only absence of observed boundary");
    const auto marker = probe.CaptureRawPose();
    const auto stable = probe.BindPose(marker, Sample(), raw, wire, 2);
    require(stable.assessment == PoseBindingAssessment::NO_OBSERVED_BOUNDARY_SINCE_CAPTURE &&
        stable.bound.coverageIncomplete && stable.capture.captureOrdinal == 2, "no physical continuity proof");
    require(probe.BindPose(marker, Sample(), raw, wire, 2).assessment ==
        PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE, "repeated marker cannot claim a new binding");
    const auto issued = probe.CaptureRawPose();
    const HmdRawPoseCaptureMarker altered{"A", issued.captureOrdinal, issued.observationSequence + 1,
        issued.detectedBoundaryGeneration};
    auto bad = probe.BindPose(altered, Sample(), raw, wire, 2);
    require(bad.assessment == PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE &&
        !bad.observedBoundarySinceCapture, "altered same-owner issuance fails closed");
    const HmdRawPoseCaptureMarker zeroOrdinal{"A", 0, 0, 0};
    require(probe.BindPose(zeroOrdinal, Sample(), raw, wire, 2).assessment ==
        PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE, "unissued marker cannot compare");
    const auto skipped = probe.CaptureRawPose();
    auto lost = Sample(); lost.trackingPoseValid = false; lost.deviceConnected = false;
    const auto seq = probe.Snapshot().observationSequence;
    probe.Observe(lost); // Adapter's availability-only path: no BindAndSend, no fabricated binding.
    require(probe.Snapshot().observationSequence == seq + 1, "tracking loss only observes availability");
    const auto recovered = probe.CaptureRawPose();
    require(recovered.captureOrdinal == skipped.captureOrdinal + 1, "no-send capture ordinal consumed by transaction");
    require(probe.BindPose(skipped, Sample(), raw, wire, 2).assessment ==
        PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE, "next transaction invalidates unused marker");
    probe.BindPose(recovered, Sample(), raw, wire, 2);

    const auto eventMarker = probe.CaptureRawPose();
    probe.ObserveSignal(FrameSignal::StandingZeroPoseReset);
    auto event = probe.BindPose(eventMarker, Sample(), raw, wire, 2);
    require(event.observedBoundarySinceCapture == true && !event.boundaryDuringBind &&
        event.bound.detectedBoundaryGeneration == eventMarker.detectedBoundaryGeneration + 1 &&
        event.lastObservedBoundarySinceCapture->lastSignal == FrameSignal::StandingZeroPoseReset &&
        event.assessment == PoseBindingAssessment::OBSERVED_BOUNDARY_SINCE_CAPTURE, "event between capture and bind");
    const auto universeMarker = probe.CaptureRawPose();
    auto universe = probe.BindPose(universeMarker, Sample(20), raw, wire, 2);
    require(universe.observedBoundarySinceCapture == true && universe.boundaryDuringBind &&
        universe.bound.universeChanged, "universe transition at final observation retains reason");
    auto changed = Sample(20); changed.appliedTransform->tx = 7;
    auto transform = probe.BindPose(probe.CaptureRawPose(), changed, raw, wire, 2);
    require(transform.boundaryDuringBind && transform.bound.transformChanged &&
        transform.observedBoundarySinceCapture == true, "same-ID transform transition during bind");
    const auto multipleMarker = probe.CaptureRawPose();
    probe.ObserveSignal(FrameSignal::SeatedZeroPoseReset);
    auto multiple = probe.BindPose(multipleMarker, Sample(30), raw, wire, 2);
    require(multiple.observedBoundarySinceCapture == true && multiple.boundaryDuringBind &&
        multiple.bound.universeChanged && multiple.bound.transformChanged &&
        multiple.bound.detectedBoundaryGeneration == multipleMarker.detectedBoundaryGeneration + 2,
        "event plus final universe/transform reasons retained, one generation per observation");
    const auto sampledUniverseMarker = probe.CaptureRawPose();
    probe.Observe(Sample(40));
    auto sampledUniverse = probe.BindPose(sampledUniverseMarker, Sample(40), raw, wire, 2);
    require(sampledUniverse.observedBoundarySinceCapture == true && !sampledUniverse.boundaryDuringBind &&
        !sampledUniverse.bound.universeChanged && sampledUniverse.lastObservedBoundarySinceCapture->universeChanged,
        "universe condition observed before bind retains its reason separately from final pose flags");
    const auto back = probe.BindPose(probe.CaptureRawPose(), Sample(10), raw, wire, 2);
    require(back.bound.universeChanged && back.bound.detectedBoundaryGeneration > universe.bound.detectedBoundaryGeneration,
        "A-B-A does not restore earlier generation");
    const auto foreign = MonakaHmdFrameProbe("other").CaptureRawPose();
    auto mismatch = probe.BindPose(foreign, Sample(), raw, wire, 2);
    require(!mismatch.ownerMatches && !mismatch.observedBoundarySinceCapture &&
        mismatch.assessment == PoseBindingAssessment::CAPTURE_OWNER_MISMATCH, "no cross-owner comparison");
    MonakaHmdFrameProbe restart("new-owner");
    auto restarted = restart.BindPose(probe.CaptureRawPose(), Sample(), raw, wire, 2);
    require(restarted.assessment == PoseBindingAssessment::CAPTURE_OWNER_MISMATCH,
        "observer recreation invalidates old marker even at same counters");

    auto failed = Sample(); failed.lookup = TransformLookupState::RefreshFailed;
    auto failure = probe.BindPose(probe.CaptureRawPose(), failed, raw, wire, 2);
    require(failure.bound.proofState == FrameProofState::TransformLookupFailed &&
        failure.bound.values.appliedTransform->tx == 1 && failure.bound.values.appliedCacheUniverseId == 10 &&
        failure.assessment == PoseBindingAssessment::FRAME_OBSERVATION_UNAVAILABLE, "failure retains old applied cache");
    for (int component = 0; component != 4; ++component) {
        auto invalid = Sample();
        switch (component) {
        case 0: invalid.appliedTransform->tx = Float(0x7fc12345); break;
        case 1: invalid.appliedTransform->ty = Float(0x7f800000); break;
        case 2: invalid.appliedTransform->tz = Float(0xff800000); break;
        case 3: invalid.appliedTransform->yaw = Float(0x7fc54321); break;
        }
        auto result = probe.BindPose(probe.CaptureRawPose(), invalid, raw, wire, 1);
        require(result.bound.proofState == FrameProofState::InvalidTransform &&
            !result.bound.values.appliedTransform->IsFinite() && result.dataSource == 1,
            "invalid metadata never substitutes identity or changes modality");
    }
    MonakaHmdFrameProbe exact("bits");
    auto zero = Sample(); zero.appliedTransform->yaw = 0.0f; exact.Observe(zero);
    const auto zeroMarker = exact.CaptureRawPose(); zero.appliedTransform->yaw = -0.0f;
    auto signedZero = exact.BindPose(zeroMarker, zero, raw, wire, 2);
    require(signedZero.boundaryDuringBind && signedZero.bound.transformChanged, "signed zero transform boundary");
    const auto reconnectMarker = exact.CaptureRawPose(); // Reconnect has no input in either observer API.
    auto reconnect = exact.BindPose(reconnectMarker, zero, raw, wire, 2);
    require(reconnect.observedBoundarySinceCapture == false, "transport reconnect cannot advance frame generation");
    require(stable.bound.detectedBoundaryGeneration == 0 && stable.bound.values.universeId == 10 &&
        stable.capture.captureOrdinal == 2 && std::bit_cast<uint32_t>(stable.rawPose.qw) == 0x7fc12345,
        "held binding is copied and immutable across later observations");
}

static void AdapterAndPayload() {
    CaptureLogger logger;
    HmdFrameProbeDiagnostics d("adapter", false, true);
    d.ObservePose(Sample(), false, logger);
    messages::ProtobufMessage message;
    auto* p = message.mutable_position();
    p->set_tracker_id(0); p->set_data_source(messages::Position_DataSource_FULL);
    // Identical legacy float/cast inputs. The production preservation script also checks every setter expression.
    p->set_x(wire.px); p->set_y(wire.py); p->set_z(wire.pz);
    p->set_qx(wire.qx); p->set_qy(wire.qy); p->set_qz(wire.qz); p->set_qw(wire.qw);
    const auto disabledBytes = message.SerializeAsString();
    int calls = 0;
    const auto bind = [&](const HmdRawPoseCaptureMarker& marker, const FrameProbeObservation& input) {
        return d.BindAndSend(marker, input, raw, wire, static_cast<int32_t>(p->data_source()), logger, [&] {
            ++calls;
            require(message.SerializeAsString() == disabledBytes, "binding callback receives unchanged protobuf bytes");
        });
    };
    auto b = bind(d.CaptureRawPose(), Sample());
    require(calls == 1 && b.dataSource == static_cast<int32_t>(messages::Position_DataSource_FULL), "one callback/source preserved");
    const auto line = logger.lines.back();
    std::istringstream fields(line);
    std::set<std::string> keys;
    std::string field;
    while (fields >> field) {
        if (const auto equals = field.find('='); equals != std::string::npos)
            require(keys.insert(field.substr(0, equals)).second, "binding trace has no ambiguous duplicate key");
    }
    for (const auto& field : {"kind=pose_binding", "capture_ordinal=1", "observed_boundary_since_capture=false",
        "raw_pos_f32_bits=80000000,00000001,7f800000", "raw_q_f32_bits=3f800001,40000002,c0400003,7fc12345",
        "wire_pos_f32_bits=3e800004,be800005,00000006", "wire_q_f32_bits=3f000007,bf000008,3f400009,3f60000a",
        "coverage_incomplete=1"}) require(line.find(field) != std::string::npos, "exact raw/wire binary32 and xyzw order");
    const auto stale = d.CaptureRawPose(); bind(stale, Sample());
    auto reused = bind(stale, Sample());
    require(reused.assessment == PoseBindingAssessment::CAPTURE_MARKER_REUSED_OR_STALE && calls == 3,
        "metadata reuse still sends once");
    auto foreign = MonakaHmdFrameProbe("foreign").CaptureRawPose();
    auto mismatch = bind(foreign, Sample());
    require(mismatch.assessment == PoseBindingAssessment::CAPTURE_OWNER_MISMATCH && calls == 4,
        "owner mismatch still sends once");
    auto failed = Sample(); failed.lookup = TransformLookupState::RefreshFailed;
    require(bind(d.CaptureRawPose(), failed).bound.proofState == FrameProofState::TransformLookupFailed && calls == 5,
        "failed lookup keeps legacy send");
    auto invalid = Sample(); invalid.appliedTransform->yaw = Float(0x7fc12345);
    require(bind(d.CaptureRawPose(), invalid).bound.proofState == FrameProofState::InvalidTransform && calls == 6,
        "invalid transform metadata keeps exact legacy payload");
    const auto beforeEvent = d.CaptureRawPose();
    std::thread event([&] { d.ObserveEvent(vr::VREvent_StandingZeroPoseReset, logger); });
    event.join();
    auto observed = bind(beforeEvent, Sample());
    require(observed.observedBoundarySinceCapture == true && calls == 7, "event completes before bind critical section");
    // Exceptional wire bits are copied without arithmetic/normalization by binding or protobuf.
    p->set_data_source(messages::Position_DataSource_IMU);
    p->set_x(raw.px); p->set_y(raw.py); p->set_z(raw.pz);
    p->set_qx(raw.qx); p->set_qy(raw.qy); p->set_qz(raw.qz); p->set_qw(raw.qw);
    const auto specialBytes = message.SerializeAsString();
    const auto special = d.BindAndSend(d.CaptureRawPose(), Sample(), wire, raw,
        static_cast<int32_t>(p->data_source()), logger, [&] {
            ++calls;
            require(message.SerializeAsString() == specialBytes, "special-bit payload also unchanged");
        });
    require(special.dataSource == static_cast<int32_t>(messages::Position_DataSource_IMU) && calls == 8 &&
        logger.lines.back().find("wire_q_f32_bits=3f800001,40000002,c0400003,7fc12345") != std::string::npos &&
        logger.lines.back().find("wire_pos_f32_bits=80000000,00000001,7f800000") != std::string::npos,
        "IMU numeric source and wire signed zero/subnormal/NaN payload/infinity preserved");
    const std::array<uint32_t, 7> expected{0x80000000, 0x00000001, 0x7f800000, 0x3f800001, 0x40000002, 0xc0400003, 0x7fc12345};
    const std::array<float, 7> actual{p->x(), p->y(), p->z(), p->qx(), p->qy(), p->qz(), p->qw()};
    for (size_t i = 0; i < actual.size(); ++i)
        require(std::bit_cast<uint32_t>(actual[i]) == expected[i], "every Position component has exact expected bits");

    CaptureLogger quietLogger;
    HmdFrameProbeDiagnostics quiet("quiet", true, false);
    quiet.ObservePose(Sample(), false, quietLogger);
    for (int i = 0; i != 5; ++i)
        quiet.BindAndSend(quiet.CaptureRawPose(), Sample(), raw, wire, 1, quietLogger, [] {});
    int associations = 0;
    for (const auto& log : quietLogger.lines) {
        require(log.find("kind=pose_binding") == std::string::npos, "trace off has no per-pose binding line");
        associations += log.find("kind=pose_association") != std::string::npos;
    }
    require(associations == 1, "existing 1 Hz association behavior remains independent");
    const auto beforeLoss = quietLogger.lines.size();
    auto lost = Sample(); lost.trackingPoseValid = false;
    quiet.CaptureRawPose(); quiet.ObservePose(lost, false, quietLogger);
    require(quietLogger.lines.size() == beforeLoss + 1 &&
        quietLogger.lines.back().find("kind=state") != std::string::npos, "no-send availability produces no binding/association");
}

static void OrderedSend() {
    CaptureLogger logger;
    HmdFrameProbeDiagnostics d("ordered", false, true);
    d.ObservePose(Sample(), false, logger);
    const auto marker = d.CaptureRawPose();
    std::latch sendEntered(1), lockAttempted(1), releaseSend(1);
    std::atomic<bool> eventFinished{false}, lockWasBlocked{false};
    std::optional<HmdObservedPoseFrameBinding> held;
    std::thread sender([&] {
        held.emplace(d.BindAndSend(marker, Sample(), raw, wire, 2, logger, [&] {
            sendEntered.count_down();
            releaseSend.wait();
        }));
    });
    std::thread event([&] {
        sendEntered.wait();
        // Real failed try_lock proves the production mutex is held during the fake send.
        // No test mutex is held while entering the probe; latches do not invert lock ordering.
        lockWasBlocked = !HmdFrameProbeDiagnosticsTestAccess::TryLock(d);
        lockAttempted.count_down();
        d.ObserveEvent(vr::VREvent_StandingZeroPoseReset, logger);
        eventFinished = true;
    });
    lockAttempted.wait();
    const bool blocked = lockWasBlocked && !eventFinished;
    releaseSend.count_down();
    sender.join(); event.join();
    require(blocked && eventFinished && held->observedBoundarySinceCapture == false && !held->boundaryDuringBind,
        "event cannot mutate observer until send callback exits");
    // An event after marker but before next bind must be visible on the next transaction.
    auto next = d.BindAndSend(d.CaptureRawPose(), Sample(), raw, wire, 2, logger, [] {});
    require(next.capture.detectedBoundaryGeneration == held->bound.detectedBoundaryGeneration + 1 &&
        next.bound.detectedBoundaryGeneration == next.capture.detectedBoundaryGeneration,
        "next pose sees post-send event generation without retagging held binding");
}

int main() {
    try {
        CoreMatrix(); AdapterAndPayload(); OrderedSend();
        std::cout << "PASS HMD pose binding matrix; exact diagnostics and deterministic bind/send ordering; no physical continuity proof\n";
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
