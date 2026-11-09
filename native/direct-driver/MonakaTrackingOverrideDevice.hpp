#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>
#include <utility>
#include "IVRDevice.hpp"
#include "MonakaDirectPose.hpp"
#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace monaka {
inline constexpr char OverrideDriverName[] = "slimevr";
inline constexpr char OverrideCapability[] = "monaka-tracking-override-v1";
inline constexpr char OverrideActivated[] = "monaka-tracking-override-activated-v1";
inline constexpr char OverrideLease[] = "monaka-tracking-override-lease-v1";

inline int64_t OverrideCounter() {
#ifdef _WIN32
    LARGE_INTEGER value{};
    if (QueryPerformanceCounter(&value)) return value.QuadPart;
#endif
    return -1;
}

inline int OverrideId(std::string_view serial) {
    if (serial == "monaka-override-v1-left") return -1;
    if (serial == "monaka-override-v1-right") return -2;
    return 0;
}

inline vr::DriverPose_t MakeOverridePose(const messages::Position& position) {
    // The operator confirms the admitted MVIP world is already the target raw world.
    // The normal SlimeVR standing/chaperone conversion does not apply to this device.
    auto pose = SlimeVRDriver::IVRDevice::MakeDefaultPose(true, false);
    if (position.has_data_source() && position.data_source() == messages::Position::FULL) {
        ApplyDirect(position, pose);
    }
    return pose;
}

class TrackingOverrideDevice final : public SlimeVRDriver::IVRDevice {
public:
    TrackingOverrideDevice(std::string serial, const std::atomic_uint64_t& bridge_epoch)
        : serial_(std::move(serial)), bridge_epoch_(bridge_epoch) {}

    std::string GetSerial() override { return serial_; }
    DeviceType GetDeviceType() override { return DeviceType::TRACKER; }
    vr::TrackedDeviceIndex_t GetDeviceIndex() override { return index_.load(); }
    int GetDeviceId() override { return OverrideId(serial_); }
    void SetDeviceId(int) override {}

    vr::EVRInitError Activate(uint32_t index) override {
        auto* properties = vr::VRProperties();
        const auto container = properties->TrackedDeviceToPropertyContainer(index);
        if (properties->SetStringProperty(container, vr::Prop_TrackingSystemName_String, OverrideDriverName) != vr::TrackedProp_Success ||
            properties->SetStringProperty(container, vr::Prop_SerialNumber_String, serial_.c_str()) != vr::TrackedProp_Success ||
            properties->SetStringProperty(container, vr::Prop_ModelNumber_String, "MonakaVR Tracking Override Source") != vr::TrackedProp_Success ||
            properties->SetStringProperty(container, vr::Prop_ManufacturerName_String, "MonakaVR") != vr::TrackedProp_Success) {
            return vr::VRInitError_Driver_Failed;
        }
        // A pose-only GenericTracker has no input profile, role hint or input components.
        last_pose_.store({MakeDefaultPose(true, false), 0, 0});
        index_.store(index);
        return vr::VRInitError_None;
    }

    void Deactivate() override {
        index_.store(vr::k_unTrackedDeviceIndexInvalid);
        last_pose_.store({MakeDefaultPose(false, false), 0, 0});
    }
    void EnterStandby() override {}
    void* GetComponent(const char*) override { return nullptr; }
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (size > 0) response[0] = '\0';
    }
    vr::DriverPose_t GetPose() override {
        const auto snapshot = last_pose_.load();
        return snapshot.pose.poseIsValid && !Current(snapshot) ? MakeDefaultPose(true, false) : snapshot.pose;
    }

    // Stored on the existing bridge callback thread and consumed by exactly one Position.
    void OfferLease(int64_t deadline, uint64_t epoch) {
        pending_deadline_ = deadline;
        pending_epoch_ = epoch;
    }

    void PositionMessage(messages::Position& position) override {
        const int64_t deadline = std::exchange(pending_deadline_, 0);
        const auto now = OverrideCounter();
        const auto epoch = bridge_epoch_.load();
        const auto pose = (pending_epoch_ == epoch && now >= 0 && deadline > now)
            ? MakeOverridePose(position) : MakeDefaultPose(true, false);
        last_position_ = std::chrono::steady_clock::now();
        Publish({pose, deadline, epoch});
    }

    void StatusMessage(messages::TrackerStatus&) override {}
    void BatteryMessage(messages::Battery&) override {}

    // Update and PositionMessage run under the provider's existing devices_mutex_.
    void Update() override {
        const auto snapshot = last_pose_.load();
        if (snapshot.pose.poseIsValid &&
            (!Current(snapshot) ||
             std::chrono::steady_clock::now() - last_position_ >= std::chrono::milliseconds(100))) {
            Publish({MakeDefaultPose(true, false), 0, 0});
        }
    }

private:
    struct Snapshot {
        vr::DriverPose_t pose;
        int64_t deadline;
        uint64_t epoch;
    };

    bool Current(const Snapshot& snapshot) const {
        if (snapshot.epoch != bridge_epoch_.load()) return false;
        const auto now = OverrideCounter();
        return now >= 0 && snapshot.deadline > now;
    }

    void Publish(const Snapshot& snapshot) {
        const auto index = index_.load();
        if (index == vr::k_unTrackedDeviceIndexInvalid) return;
        last_pose_.store(snapshot);
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(index, snapshot.pose, sizeof(vr::DriverPose_t));
    }

    const std::string serial_;
    const std::atomic_uint64_t& bridge_epoch_;
    std::atomic<vr::TrackedDeviceIndex_t> index_{vr::k_unTrackedDeviceIndexInvalid};
    std::atomic<Snapshot> last_pose_{{MakeDefaultPose(true, false), 0, 0}};
    std::chrono::steady_clock::time_point last_position_{};
    int64_t pending_deadline_ = 0;
    uint64_t pending_epoch_ = 0;
};
}
