#include "HilAction.hpp"
#include <openvr.h>
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <objbase.h>
#include <bit>
#include <iostream>

using namespace monaka::hil;
Stamp Now() {
    LARGE_INTEGER ticks{}, frequency{};
    if (!QueryPerformanceCounter(&ticks) || !QueryPerformanceFrequency(&frequency))
        throw std::runtime_error("QPC unavailable");
    return {std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(),
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::system_clock::now().time_since_epoch()).count(),
        ticks.QuadPart, frequency.QuadPart};
}
std::string Owner() {
    GUID guid{};
    if (FAILED(CoCreateGuid(&guid))) throw std::runtime_error("GUID generation failed");
    std::ostringstream out;
    out << "hil-" << std::hex << std::setfill('0') << std::setw(8) << guid.Data1 << '-'
        << std::setw(4) << guid.Data2 << '-' << std::setw(4) << guid.Data3 << '-';
    for (auto b : guid.Data4) out << std::setw(2) << unsigned(b);
    return out.str();
}
std::string MatrixBits(const vr::HmdMatrix34_t& m) {
    std::ostringstream out; out << '[';
    for (int i = 0; i < 12; ++i) {
        if (i) out << ',';
        std::ostringstream bits; bits << std::hex << std::setw(8) << std::setfill('0') << std::bit_cast<uint32_t>(m.m[i / 4][i % 4]);
        out << Json(bits.str());
    }
    out << ']'; return out.str();
}
class OpenVrRuntime final : public Runtime {
    bool initialized_ = false;
    vr::IVRSystem* system_ = nullptr;
    vr::IVRChaperone* chaperone_ = nullptr;
public:
    ~OpenVrRuntime() override { if (initialized_) vr::VR_Shutdown(); }
    bool Open(std::string& error) override {
        if (!vr::VR_IsRuntimeInstalled()) { error = "OpenVR runtime not installed"; return false; }
        vr::EVRInitError init = vr::VRInitError_None;
        system_ = vr::VR_Init(&init, vr::VRApplication_Background);
        if (init != vr::VRInitError_None || !system_) {
            error = std::to_string(int(init)) + ":" + vr::VR_GetVRInitErrorAsSymbol(init); return false;
        }
        initialized_ = true;
        vr::EVRInitError interfaceError = vr::VRInitError_None;
        chaperone_ = static_cast<vr::IVRChaperone*>(vr::VR_GetGenericInterface(vr::IVRChaperone_Version, &interfaceError));
        if (!chaperone_ || interfaceError != vr::VRInitError_None) {
            error = "IVRChaperone_004 unavailable:" + std::to_string(int(interfaceError)); return false;
        }
        vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount]{};
        system_->GetDeviceToAbsoluteTrackingPose(vr::TrackingUniverseSeated, 0.0f, poses, vr::k_unMaxTrackedDeviceCount);
        const auto& hmd = poses[vr::k_unTrackedDeviceIndex_Hmd];
        if (!hmd.bDeviceIsConnected || !hmd.bPoseIsValid) {
            error = "HMD not connected or pose invalid; no action"; return false;
        }
        return true;
    }
    std::string Inspect() override {
        auto seated = system_->GetSeatedZeroPoseToStandingAbsoluteTrackingPose();
        auto raw = system_->GetRawZeroPoseToStandingAbsoluteTrackingPose();
        return "\"snapshot_atomic\":false,\"seated_to_standing_f32_bits\":" + MatrixBits(seated)
            + ",\"raw_to_standing_f32_bits\":" + MatrixBits(raw);
    }
    void ResetSeated() override { chaperone_->ResetZeroPose(vr::TrackingUniverseSeated); }
    void ResetStanding() override { chaperone_->ResetZeroPose(vr::TrackingUniverseStanding); }
};
int main(int argc, char** argv) {
    try {
        std::vector<std::string> args(argv + 1, argv + argc);
        auto options = Parse(args);
        if (options.help) {
            std::cout << "Test-only OpenVR HIL action helper (no driver install or automatic SteamVR startup).\n"
                "--action mark --label LABEL\n--action inspect\n"
                "--action seated-reset|standing-reset --allow-persistent-chaperone-change\n"
                "ResetZeroPose overwrites the user's saved zero pose. No rollback is provided.\n"
                "Reset returns void; returned is NOT success/effect acknowledgement.\n"
                "Markers are action observations, not frame epochs or physical application times.\n"
                "stdout: MONAKA_HIL_ACTION_V1 followed by JSON; wall times are Unix ns.\n";
            return 0;
        }
        Ids ids(Owner()); OpenVrRuntime runtime;
        return Execute(options, ids, runtime, std::cout, Now);
    } catch (const std::exception& e) {
        std::cerr << "HIL action failed: " << e.what() << '\n'; return 2;
    }
}
