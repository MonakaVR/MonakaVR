#include "MonakaHmdFrameProbeDriver.hpp"
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>
#include <vector>
#include <type_traits>

using namespace monaka;
static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
static FrameProbeObservation Sample(uint64_t universe = 10) {
    return {universe, universe, AppliedUniverseTransform{1, 2, 3, 0.5f},
            TransformLookupState::CachedForObservedUniverse, true, true, vr::TrackingResult_Running_OK};
}
static_assert(!std::is_assignable_v<decltype(HmdFrameObservation::observationSequence)&, uint64_t>);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneUniverseHasChanged) == FrameSignal::ChaperoneUniverseChanged);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_SeatedZeroPoseReset) == FrameSignal::SeatedZeroPoseReset);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_StandingZeroPoseReset) == FrameSignal::StandingZeroPoseReset);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneDataHasChanged) == FrameSignal::ChaperoneDataChanged);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneTempDataHasChanged) == FrameSignal::ChaperoneTempDataChanged);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneSettingsHaveChanged) == FrameSignal::ChaperoneSettingsChanged);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneFlushCache) == FrameSignal::ChaperoneFlushCache);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneRoomSetupStarting) == FrameSignal::RoomSetupStarting);
static_assert(FrameSignalForOpenVrEvent(vr::VREvent_ChaperoneRoomSetupCommitted) == FrameSignal::RoomSetupCommitted);
static_assert(!FrameSignalForOpenVrEvent(vr::VREvent_Input_HapticVibration));
static_assert(!FrameSignalForOpenVrEvent(vr::VREvent_TrackedDeviceActivated));
static_assert(!FrameSignalForOpenVrEvent(vr::VREvent_PropertyChanged));

class CaptureLogger : public Logger {
public:
    std::vector<std::string> lines;
protected:
    void LogImpl(const char* line) override { lines.emplace_back(line); }
};

int main() {
    try {
        MonakaHmdFrameProbe probe("owner-A");
        auto initial = probe.Snapshot();
        require(initial.proofState == FrameProofState::Unknown && initial.observationSequence == 0 &&
                initial.detectedBoundaryGeneration == 0 && !initial.values.universeId &&
                !initial.values.appliedTransform && initial.coverageIncomplete, "initial unknown");
        auto a = Sample();
        auto first = probe.Observe(a);
        require(first.proofState == FrameProofState::Unknown && first.detectedBoundaryGeneration == 0, "first is baseline");
        auto stable = probe.Observe(a);
        require(stable.proofState == FrameProofState::ObservedStableInputs && stable.coverageIncomplete &&
                stable.observationSequence == 2 && stable.detectedBoundaryGeneration == 0, "stable inputs are not continuity proof");
        auto b = Sample(20);
        require(probe.Observe(b).detectedBoundaryGeneration == 1, "A to B");
        auto aba = probe.Observe(a);
        require(aba.detectedBoundaryGeneration == 2 && aba.universeChanged, "ABA never restores earlier lineage");
        require(first.observationSequence == 1 && first.values.universeId == 10, "snapshot immutable over later observations");
        a.appliedTransform->tx = 4;
        auto transformChange = probe.Observe(a);
        require(transformChange.detectedBoundaryGeneration == 3 && transformChange.proofState == FrameProofState::TransformChanged,
                "same ID changed applied transform");
        for (auto signal : {FrameSignal::SeatedZeroPoseReset, FrameSignal::StandingZeroPoseReset, FrameSignal::ChaperoneUniverseChanged}) {
            const auto before = probe.Snapshot().detectedBoundaryGeneration;
            auto reset = probe.ObserveSignal(signal);
            require(reset.detectedBoundaryGeneration == before + 1 && reset.boundarySignalObserved &&
                    reset.proofState == FrameProofState::BoundarySignalObserved && reset.values.appliedTransform == a.appliedTransform,
                    "explicit reset/universe event with identical numeric transform");
        }
        for (auto signal : {FrameSignal::ChaperoneDataChanged, FrameSignal::ChaperoneTempDataChanged,
             FrameSignal::ChaperoneSettingsChanged, FrameSignal::ChaperoneFlushCache,
             FrameSignal::RoomSetupStarting, FrameSignal::RoomSetupCommitted}) {
            const auto before = probe.Snapshot().detectedBoundaryGeneration;
            require(probe.ObserveSignal(signal).detectedBoundaryGeneration == before, "partial events do not assert origin change");
        }
        auto generation = probe.Snapshot().detectedBoundaryGeneration;
        // Pose motion and transport/source lifetime cannot enter the pure observer input type.
        vr::TrackedDevicePose_t rawPose{};
        rawPose.bPoseIsValid = true;
        rawPose.bDeviceIsConnected = true;
        rawPose.eTrackingResult = vr::TrackingResult_Running_OK;
        for (int movement = 0; movement < 100; ++movement) {
            rawPose.mDeviceToAbsoluteTracking.m[0][3] = float(movement);
            rawPose.mDeviceToAbsoluteTracking.m[0][0] = float(movement % 2);
            CopyHmdTrackingObservation(a, rawPose);
            require(probe.Observe(a).detectedBoundaryGeneration == generation, "HMD position/rotation motion excluded");
        }
        a.trackingPoseValid = false;
        a.trackingResult = vr::TrackingResult_Running_OutOfRange;
        auto loss = probe.Observe(a);
        require(!loss.values.trackingPoseValid && loss.proofState == FrameProofState::Unknown &&
                loss.detectedBoundaryGeneration == generation, "tracking loss availability only");
        a.trackingPoseValid = true;
        a.trackingResult = vr::TrackingResult_Running_OK;
        auto recovered = probe.Observe(a);
        require(recovered.detectedBoundaryGeneration == generation && recovered.coverageIncomplete, "recovery no continuity claim");
        a.universeId = 30; // Lookup for C fails; A cache is still actually applied.
        a.lookup = TransformLookupState::RefreshFailed;
        auto failure = probe.Observe(a);
        require(failure.proofState == FrameProofState::TransformLookupFailed && failure.values.appliedCacheUniverseId == 10 &&
                failure.values.appliedTransform->tx == 4 && failure.universeChanged, "failed lookup keeps visible old cache");
        require(probe.Observe(a).proofState == FrameProofState::TransformLookupFailed, "old cache cannot promote failure to stable");
        a.universeId.reset(); a.lookup = TransformLookupState::UniverseUnavailable;
        auto missing = probe.Observe(a);
        require(!missing.values.universeId && missing.values.appliedTransform && missing.proofState == FrameProofState::Unknown,
                "unavailable property distinct from cached ID");
        a.universeId = 10; a.lookup = TransformLookupState::CachedForObservedUniverse;
        require(probe.Observe(a).universeChanged, "known IDs compared across unavailable gap without claiming gap coverage");

        for (int component = 0; component < 4; ++component) {
            for (float invalid : {std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity(),
                                  std::numeric_limits<float>::quiet_NaN()}) {
                MonakaHmdFrameProbe invalidProbe("invalid");
                auto input = Sample(); invalidProbe.Observe(input);
                auto& t = *input.appliedTransform;
                switch (component) {
                case 0: t.tx = invalid; break; case 1: t.ty = invalid; break;
                case 2: t.tz = invalid; break; case 3: t.yaw = invalid; break;
                }
                auto result = invalidProbe.Observe(input);
                require(result.proofState == FrameProofState::InvalidTransform && result.values.appliedTransform &&
                        !result.values.appliedTransform->IsFinite(), "nonfinite preserved, no identity substitution");
                require(invalidProbe.Observe(input).proofState == FrameProofState::InvalidTransform, "repeated nonfinite fails closed");
            }
        }
        MonakaHmdFrameProbe exact("bits");
        auto zeros = Sample(); zeros.appliedTransform->yaw = 0.0f; exact.Observe(zeros);
        zeros.appliedTransform->yaw = -0.0f;
        require(exact.Observe(zeros).detectedBoundaryGeneration == 1, "signed zero exact bits");
        zeros.appliedTransform->yaw = std::numeric_limits<float>::denorm_min();
        require(exact.Observe(zeros).detectedBoundaryGeneration == 2, "no epsilon suppression");
        auto both = zeros; both.universeId = 99; both.appliedCacheUniverseId = 99; both.appliedTransform->tx = 9;
        auto reasons = exact.Observe(both, FrameSignal::StandingZeroPoseReset);
        require(reasons.detectedBoundaryGeneration == 3 && reasons.universeChanged && reasons.transformChanged &&
                reasons.boundarySignalObserved, "one input generation retains multiple reasons");
        MonakaHmdFrameProbe restart("owner-B");
        require(restart.Snapshot().observerOwner != initial.observerOwner && restart.Snapshot().detectedBoundaryGeneration == 0,
                "restart new owner namespace, counter may start zero");
        require(NewProbeOwner() != NewProbeOwner(), "production owner allocation differs");

        CaptureLogger logger;
        HmdFrameProbeDiagnostics diagnostics("diagnostic-owner", true);
        for (int i = 0; i < 500; ++i) diagnostics.ObservePose(Sample(), true, logger);
        require(logger.lines.size() == 3, "initial, stable, one rate-limited pose association; no 2ms spam");
        require(logger.lines[1].find("poseProbeObservationSequence=1") != std::string::npos &&
                logger.lines[1].find("value_context=same_raw_pose_sample") != std::string::npos &&
                logger.lines[1].find("applied_f32_bits=3f800000,40000000,40400000,3f000000") != std::string::npos,
                "machine-readable exact pose association");
        const auto logCount = logger.lines.size();
        diagnostics.ObserveEvent(vr::VREvent_Input_HapticVibration, logger);
        diagnostics.ObserveEvent(vr::VREvent_TrackedDeviceActivated, logger);
        diagnostics.ObserveEvent(vr::VREvent_PropertyChanged, logger);
        require(logger.lines.size() == logCount, "unrelated events ignored, no observer input");
        // Transport callbacks are absent; taking no input cannot advance the observer.
        auto beforeTransport = probe.Snapshot();
        auto afterTransport = probe.Snapshot();
        require(beforeTransport.observationSequence == afterTransport.observationSequence &&
                beforeTransport.detectedBoundaryGeneration == afterTransport.detectedBoundaryGeneration,
                "transport reconnect excluded from observer API");
        diagnostics.ObserveEvent(vr::VREvent_StandingZeroPoseReset, logger);
        require(logger.lines.back().find("boundary=1") != std::string::npos &&
                logger.lines.back().find("value_context=last_pose_sample") != std::string::npos, "event value context explicit");
        // Exercise the same runtime adapter's two thread entry points without an OpenVR runtime/context.
        CaptureLogger concurrentLogger;
        HmdFrameProbeDiagnostics concurrent("concurrent-owner", false);
        concurrent.ObservePose(Sample(), false, concurrentLogger);
        std::thread events([&] { for (int i = 0; i < 100; ++i) concurrent.ObserveEvent(vr::VREvent_SeatedZeroPoseReset, concurrentLogger); });
        std::thread poses([&] { for (int i = 0; i < 100; ++i) concurrent.ObservePose(Sample(), false, concurrentLogger); });
        events.join(); poses.join();
        concurrent.ObserveEvent(vr::VREvent_StandingZeroPoseReset, concurrentLogger);
        require(concurrentLogger.lines.back().find("boundary=101") != std::string::npos &&
                concurrentLogger.lines.back().find("seq=202") != std::string::npos, "serialized events and pose observations");
        std::cout << "PASS HMD frame probe: observed changes only, coverage incomplete, never trusted frame epoch\n";
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
