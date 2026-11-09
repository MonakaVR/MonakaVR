#include "MonakaTrackingOverrideDevice.hpp"
#include "VRDriver.hpp"
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void invalid(const vr::DriverPose_t& pose) {
    require(!pose.poseIsValid && pose.deviceIsConnected && pose.result == vr::TrackingResult_Running_OutOfRange,
        "Unavailable override pose");
    require(pose.vecPosition[0] == 0 && pose.vecPosition[1] == 0 && pose.vecPosition[2] == 0, "No retained XYZ");
}

class OpenVRFixture final : public vr::IVRDriverContext, public vr::IVRServerDriverHost,
    public vr::IVRProperties, public vr::IVRDriverLog {
public:
    std::vector<std::string> serials;
    std::map<vr::PropertyContainerHandle_t, std::map<vr::ETrackedDeviceProperty, std::string>> properties;
    vr::DriverPose_t updated{};
    uint32_t updated_index = 0;

    void* GetGenericInterface(const char* version, vr::EVRInitError*) override {
        if (std::strcmp(version, vr::IVRServerDriverHost_Version) == 0) return static_cast<vr::IVRServerDriverHost*>(this);
        if (std::strcmp(version, vr::IVRProperties_Version) == 0) return static_cast<vr::IVRProperties*>(this);
        if (std::strcmp(version, vr::IVRDriverLog_Version) == 0) return static_cast<vr::IVRDriverLog*>(this);
        throw std::runtime_error("Unexpected OpenVR interface lookup");
    }
    vr::DriverHandle_t GetDriverHandle() override { return 1; }
    bool TrackedDeviceAdded(const char* serial, vr::ETrackedDeviceClass type, vr::ITrackedDeviceServerDriver* device) override {
        require(type == vr::TrackedDeviceClass_GenericTracker, "Registration must use the generic tracker class");
        serials.emplace_back(serial);
        return device->Activate(static_cast<uint32_t>(serials.size())) == vr::VRInitError_None;
    }
    void TrackedDevicePoseUpdated(uint32_t index, const vr::DriverPose_t& pose, uint32_t size) override {
        require(size == sizeof(vr::DriverPose_t), "DriverPose size");
        updated = pose;
        updated_index = index;
    }
    vr::ETrackedPropertyError WritePropertyBatch(vr::PropertyContainerHandle_t container, vr::PropertyWrite_t* batch, uint32_t count) override {
        for (uint32_t n = 0; n < count; ++n) {
            require(batch[n].unTag == vr::k_unStringPropertyTag, "Only pose source descriptor strings");
            properties[container][batch[n].prop] = static_cast<const char*>(batch[n].pvBuffer);
            batch[n].eError = vr::TrackedProp_Success;
        }
        return vr::TrackedProp_Success;
    }
    vr::ETrackedPropertyError ReadPropertyBatch(vr::PropertyContainerHandle_t, vr::PropertyRead_t*, uint32_t) override {
        return vr::TrackedProp_UnknownProperty;
    }
    const char* GetPropErrorNameFromEnum(vr::ETrackedPropertyError) override { return "fixture"; }
    vr::PropertyContainerHandle_t TrackedDeviceToPropertyContainer(vr::TrackedDeviceIndex_t index) override { return index; }
    void Log(const char*) override {}
    void VsyncEvent(double) override {}
    void VendorSpecificEvent(uint32_t, vr::EVREventType, const vr::VREvent_Data_t&, double) override {}
    bool IsExiting() override { return false; }
    bool PollNextEvent(vr::VREvent_t*, uint32_t) override { return false; }
    void GetRawTrackedDevicePoses(float, vr::TrackedDevicePose_t*, uint32_t) override {}
    void RequestRestart(const char*, const char*, const char*, const char*) override {}
    uint32_t GetFrameTimings(vr::Compositor_FrameTiming*, uint32_t) override { return 0; }
    void SetDisplayEyeToHead(uint32_t, const vr::HmdMatrix34_t&, const vr::HmdMatrix34_t&) override {}
    void SetDisplayProjectionRaw(uint32_t, const vr::HmdRect2_t&, const vr::HmdRect2_t&) override {}
    void SetRecommendedRenderTargetSize(uint32_t, uint32_t, uint32_t) override {}
};

static void mockRegistration(messages::Position full) {
    OpenVRFixture fixture;
    vr::VRDriverContext() = &fixture;
    vr::OpenVRInternal_ModuleServerDriverContext().Clear();
    std::atomic_uint64_t epoch{1};
    SlimeVRDriver::VRDriver driver;
    auto left = std::make_shared<monaka::TrackingOverrideDevice>("monaka-override-v1-left", epoch);
    auto right = std::make_shared<monaka::TrackingOverrideDevice>("monaka-override-v1-right", epoch);
    require(driver.AddDevice(left) && driver.AddDevice(right), "Real driver registration route");
    require(driver.AddDevice(std::make_shared<monaka::TrackingOverrideDevice>(left->GetSerial(), epoch)) &&
        fixture.serials.size() == 2, "Reconnect reuses registered serial");
    for (const auto& [index, properties] : fixture.properties) {
        require(properties.at(vr::Prop_SerialNumber_String) == fixture.serials.at(index - 1) &&
            properties.at(vr::Prop_TrackingSystemName_String) == "slimevr" &&
            !properties.contains(vr::Prop_InputProfilePath_String) &&
            !properties.contains(vr::Prop_ControllerRoleHint_Int32), "Proof properties and no input role/profile");
    }
#ifdef _WIN32
    LARGE_INTEGER frequency{};
    require(QueryPerformanceFrequency(&frequency) != 0, "Windows shared monotonic counter");
    const auto freshDeadline = [&] { return monaka::OverrideCounter() + frequency.QuadPart; };
    left->PositionMessage(full);
    invalid(fixture.updated);
    left->OfferLease(monaka::OverrideCounter() - 1, epoch);
    left->PositionMessage(full);
    invalid(fixture.updated);
    left->OfferLease(freshDeadline(), epoch);
    left->PositionMessage(full);
    require(fixture.updated_index == 1 && fixture.updated.poseIsValid && !right->GetPose().poseIsValid, "Actual DriverHost pose update");
    left->PositionMessage(full);
    invalid(fixture.updated);
    left->OfferLease(freshDeadline(), epoch);
    left->PositionMessage(full);
    ++epoch;
    invalid(left->GetPose());
    left->Update();
    invalid(fixture.updated);
    right->OfferLease(freshDeadline(), epoch);
    right->PositionMessage(full);
    require(fixture.updated_index == 2 && fixture.updated.poseIsValid, "Right hand update remains independent");
    right->OfferLease(monaka::OverrideCounter() + frequency.QuadPart / 40, epoch);
    right->PositionMessage(full);
    std::this_thread::sleep_for(std::chrono::milliseconds(30));
    invalid(right->GetPose());
    right->Update();
    invalid(fixture.updated);
    right->OfferLease(freshDeadline(), epoch);
    right->PositionMessage(full);
    std::this_thread::sleep_for(std::chrono::milliseconds(110));
    right->Update();
    invalid(fixture.updated);
    right->OfferLease(freshDeadline(), epoch);
    right->PositionMessage(full);
    right->Deactivate();
    require(!right->GetPose().poseIsValid, "Deactivation clears cached valid pose");
#endif
    vr::CleanupDriverContext();
}

int main(int argc, char** argv) {
    try {
        std::atomic_uint64_t epoch{1};
        monaka::TrackingOverrideDevice left("monaka-override-v1-left", epoch);
        monaka::TrackingOverrideDevice right("monaka-override-v1-right", epoch);
        require(left.GetDeviceType() == DeviceType::TRACKER && right.GetDeviceType() == DeviceType::TRACKER, "Pose source device class");
        require(left.GetDeviceId() == -1 && right.GetDeviceId() == -2 && left.GetSerial() != right.GetSerial(), "Stable distinct identities");
        require(!monaka::OverrideId("monaka-direct:resolved-v1:LEFT_HAND") &&
                !monaka::OverrideId("monaka-override-v1-left-other"), "Only exact reserved serials");
        invalid(left.GetPose());
        invalid(right.GetPose());
        messages::Position message;
        message.set_data_source(messages::Position::FULL);
        message.set_x(1); message.set_y(2); message.set_z(3); message.set_qw(1);
        auto pose = monaka::MakeOverridePose(message);
        require(pose.poseIsValid && pose.qRotation.w == 1 && pose.vecPosition[0] == 1, "Complete FULL");
        require(pose.qWorldFromDriverRotation.w == 1 && pose.vecWorldFromDriverTranslation[0] == 0 &&
            pose.qDriverFromHeadRotation.w == 1 && pose.vecVelocity[0] == 0 && pose.poseTimeOffset == 0,
            "Confirmed world, no fabricated velocity or time offset");
        mockRegistration(message);
        message.clear_z(); invalid(monaka::MakeOverridePose(message));
        message.set_z(std::numeric_limits<float>::infinity()); invalid(monaka::MakeOverridePose(message));
        message.set_z(3); message.set_qw(0); invalid(monaka::MakeOverridePose(message));
        message.set_qw(1); message.clear_data_source(); invalid(monaka::MakeOverridePose(message));
        message.set_data_source(messages::Position::IMU); invalid(monaka::MakeOverridePose(message));
        message.set_data_source(messages::Position::NONE); invalid(monaka::MakeOverridePose(message));
        if (argc == 2) {
            for (int index = 0; index < 4; ++index) {
                std::ifstream file(std::filesystem::path(argv[1]) / (std::to_string(index) + ".pb"), std::ios::binary);
                messages::ProtobufMessage capture;
                require(file.good() && capture.ParseFromIstream(&file) && capture.has_position(), "JVM frame capture");
                require(capture.position().tracker_id() == (index == 1 ? -2 : -1), "JVM hand routing");
                pose = monaka::MakeOverridePose(capture.position());
                if (index == 2) {
                    invalid(pose);
                } else {
                    require(pose.poseIsValid && pose.vecPosition[0] == index + 1 && pose.qRotation.w == 1, "JVM FULL exact");
                }
            }
            std::cout << "PASS JVM serializer/native decoder, left/right/lost/recovery\n";
        }
        std::cout << "PASS native override registration, identity, full-only pose and lease invalidation\n";
    } catch (const std::exception& error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
