#include "MonakaDirectPose.hpp"
#include <iostream>
#include <limits>
#include <stdexcept>
#include <filesystem>
#include <fstream>

static void require(bool ok, const char* why) { if (!ok) throw std::runtime_error(why); }
int main(int argc, char** argv) {
    try {
        require(monaka::IsDirect("monaka-direct:resolved-v1:HIP"), "Direct prefix");
        require(!monaka::IsDirect("human://HIP"), "Legacy excluded");
        require(!monaka::IsDirect("monaka-direct:other"), "Other output contract excluded");
        vr::DriverPose_t pose{};
        messages::Position p;
        p.set_data_source(messages::Position::FULL);
        p.set_x(1); p.set_y(2); p.set_z(3); p.set_qw(1);
        // Exercise the real generated protobuf codec before every driver conversion.
        auto apply = [&] {
            messages::Position decoded;
            require(decoded.ParseFromString(p.SerializeAsString()), "protobuf parse");
            monaka::ApplyDirect(decoded, pose);
        };
        apply();
        require(pose.poseIsValid && pose.vecPosition[0] == 1 && pose.vecPosition[2] == 3, "FULL exact position");
        p.clear_x(); p.clear_y(); p.clear_z(); p.set_data_source(messages::Position::IMU);
        p.set_qw(0); p.set_qy(1); apply();
        require(!pose.poseIsValid && pose.result == vr::TrackingResult_Fallback_RotationOnly &&
            pose.qRotation.y == 1 && pose.vecPosition[0] == 0, "IMU live rotation, no stale valid position");
        p.set_data_source(messages::Position::NONE); apply();
        require(!pose.poseIsValid && pose.deviceIsConnected && pose.result == vr::TrackingResult_Running_OutOfRange, "NONE");
        p.set_data_source(messages::Position::FULL); p.set_x(4); p.set_y(5); p.set_z(6); apply();
        require(pose.poseIsValid && pose.vecPosition[0] == 4, "FULL recovery");
        p.clear_y(); apply(); require(!pose.poseIsValid, "incomplete XYZ fail closed");
        p.set_y(std::numeric_limits<float>::infinity()); apply(); require(!pose.poseIsValid, "nonfinite position");
        p.set_y(5); p.set_qy(0); apply(); require(!pose.poseIsValid, "zero quaternion");
        p.set_qy(1); p.clear_data_source(); apply(); require(!pose.poseIsValid, "missing modality");
        p.set_data_source(messages::Position::IMU); apply(); require(pose.result != vr::TrackingResult_Fallback_RotationOnly, "IMU with XYZ fail closed");
        if (argc == 2) {
            int tracker_id = -1;
            for (int index = 0; index < 4; ++index) {
                std::ifstream file(std::filesystem::path(argv[1]) / (std::to_string(index) + ".pb"), std::ios::binary);
                messages::ProtobufMessage message;
                require(file.good() && message.ParseFromIstream(&file) && message.has_position(), "JVM protobuf capture missing/invalid");
                const auto& p = message.position();
                if (index == 0) tracker_id = p.tracker_id();
                require(p.tracker_id() == tracker_id, "JVM identity continuity");
                monaka::ApplyDirect(p, pose);
                if (index == 0 || index == 3) require(pose.poseIsValid && pose.vecPosition[0] == 3 && pose.qRotation.w == 1, "JVM FULL");
                if (index == 1) require(!pose.poseIsValid && pose.result == vr::TrackingResult_Fallback_RotationOnly && pose.qRotation.x == 1, "JVM IMU");
                if (index == 2) require(!pose.poseIsValid && pose.result == vr::TrackingResult_Running_OutOfRange, "JVM NONE");
            }
            std::cout << "PASS JVM serializer/native decoder interop FULL/IMU/NONE/FULL\n";
        }
        std::cout << "PASS direct protobuf/OpenVR conversion\n";
    } catch (const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n'; return 1; }
}
