"""Prepare an isolated, pinned SlimeVR driver overlay. Never installs or edits the source checkout."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

PIN = "dcc0f56bcb2a3196d6f92b1ed1d029faa425b931"
OPENVR_PIN = "91825305130f446f82054c1ec3d416321ace0072"
ROOT = Path(__file__).resolve().parents[1]


def git(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args], text=True,
                                   env={k.upper(): v for k, v in os.environ.items()}).strip()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def tracked_copy(source, output):
    for name in git(source, "ls-files", "-z").split("\0"):
        file = source / name
        if file.is_file():
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)


def replace(file, before, after):
    text = file.read_text(encoding="utf-8")
    require(text.count(before) == 1, f"Pinned overlay anchor mismatch: {file}: {before[:60]}")
    file.write_text(text.replace(before, after), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    require(git(source, "rev-parse", "HEAD") == PIN, "Upstream driver HEAD mismatch")
    require(not git(source, "status", "--porcelain"), "Upstream driver must be clean")
    modules = {}
    for module in ("libraries/openvr", "libraries/linalg"):
        pin = git(source, "ls-tree", "HEAD", module).split()[2]
        if module == "libraries/openvr":
            require(pin == OPENVR_PIN, "Pinned OpenVR gitlink mismatch")
        require(git(source / module, "rev-parse", "HEAD") == pin, f"Submodule HEAD mismatch: {module}")
        require(not git(source / module, "status", "--porcelain"), f"Submodule dirty: {module}")
        modules[module] = pin
    require(not output.exists(), "Output must be new; existing artifacts are never overwritten")
    require(not output.is_relative_to(source), "Output must be outside the source checkout")
    output.mkdir(parents=True)
    tracked_copy(source, output)
    for module in ("libraries/openvr", "libraries/linalg"):
        tracked_copy(source / module, output / module)
    shutil.copy2(ROOT / "native/direct-driver/MonakaDirectPose.hpp", output / "src/MonakaDirectPose.hpp")
    shutil.copy2(ROOT / "native/direct-driver/direct_pose_test.cpp", output / "direct_pose_test.cpp")
    for name in ("MonakaHmdFrameProbe.hpp", "MonakaHmdFrameProbeDriver.hpp"):
        shutil.copy2(ROOT / "native/direct-driver" / name, output / "src" / name)
    shutil.copy2(ROOT / "native/direct-driver/frame_probe_test.cpp", output / "frame_probe_test.cpp")
    shutil.copy2(ROOT / "native/direct-driver/pose_frame_binding_test.cpp", output / "pose_frame_binding_test.cpp")
    for name in ("MonakaRawHmdProviderEvidence.hpp", "MonakaRawHmdProviderDriver.hpp"):
        shutil.copy2(ROOT / "native/direct-driver" / name, output / "src" / name)
    shutil.copy2(ROOT / "native/direct-driver/raw_hmd_provider_evidence_test.cpp", output / "raw_hmd_provider_evidence_test.cpp")
    shutil.copy2(ROOT / "native/direct-driver/raw_hmd_provider_integration_test.cpp", output / "raw_hmd_provider_integration_test.cpp")
    tracker = output / "src/TrackerDevice.cpp"
    replace(tracker, '#include "TrackerDevice.hpp"', '#include "TrackerDevice.hpp"\n#include "MonakaDirectPose.hpp"')
    replace(tracker, ', last_pose_atomic_(MakeDefaultPose()) { }',
            ', last_pose_atomic_(MakeDefaultPose()) {\n'
            '    if (monaka::IsDirect(serial_)) { monaka::Unavailable(last_pose_); last_pose_atomic_ = last_pose_; }\n}')
    replace(tracker, 'pose.result = vr::ETrackingResult::TrackingResult_Running_OK;',
            'pose.result = vr::ETrackingResult::TrackingResult_Running_OK;\n'
            '    if (monaka::IsDirect(serial_)) monaka::ApplyDirect(position, pose);')
    replace(tracker, 'switch (status.status()) {',
            '// Status OK cannot manufacture a valid Direct pose; only Position can.\n'
            '    if (monaka::IsDirect(serial_) && status.status() == messages::TrackerStatus_Status_OK) return;\n'
            '    switch (status.status()) {')
    driver = output / "src/VRDriver.cpp"
    driver_header = output / "src/VRDriver.hpp"
    replace(driver_header, '#include "Logger.hpp"',
            '#include "Logger.hpp"\n#include "MonakaHmdFrameProbeDriver.hpp"\n#include "MonakaRawHmdProviderDriver.hpp"')
    replace(driver_header, '    std::optional<std::pair<uint64_t, UniverseTranslation>> current_universe_ = std::nullopt;',
            '    std::optional<std::pair<uint64_t, UniverseTranslation>> current_universe_ = std::nullopt;\n'
            '    // Created before worker startup and never reassigned during driver lifetime. Default OFF.\n'
            '    std::unique_ptr<monaka::HmdFrameProbeDiagnostics> hmd_frame_probe_;')
    replace(driver_header, 'private:\n',
            'private:\n'
            '    // Outlives worker/bridge members and their synchronous lifecycle callbacks.\n'
            '    monaka::RawHmdProviderDriver hmd_provider_;\n')
    # Generated overlay only: synchronous notifications, no changes to transport bytes/buffers.
    # Retirement cannot miss a disconnect/reconnect occurring between pose-thread polls.
    bridge_header = output / "src/bridge/BridgeClient.hpp"
    replace(bridge_header, '    void SendVersion();',
            '    void SendVersion();\n'
            '    // Set once before Start; independent provider lifecycle, no wire evidence.\n'
            '    void SetProviderLifecycleCallbacks(std::function<void()> establish, std::function<void()> retire) {\n'
            '        provider_establish_ = std::move(establish); provider_retire_ = std::move(retire);\n'
            '    }')
    replace(bridge_header, '    google::protobuf::Arena arena_;',
            '    std::function<void()> provider_establish_, provider_retire_;\n'
            '    google::protobuf::Arena arena_;')
    bridge_client = output / "src/bridge/BridgeClient.cpp"
    replace(bridge_client, '        connected_ = true;',
            '        if (provider_establish_) provider_establish_();\n        connected_ = true;')
    replace(bridge_client, '    connected_ = false;',
            '    if (provider_retire_) provider_retire_();\n    connected_ = false;')
    replace(driver, '    bridge_->Start();',
            '    bridge_->SetProviderLifecycleCallbacks(\n'
            '        [this] { hmd_provider_.Reestablish(); }, [this] { hmd_provider_.Retire(); });\n'
            '    bridge_->Start();')
    replace(driver, '    exiting_.store(true);', '    hmd_provider_.Retire();\n    exiting_.store(true);')
    replace(driver, '        vr::PropertyContainerHandle_t hmd_prop_container = vr::VRProperties()->TrackedDeviceToPropertyContainer(vr::k_unTrackedDeviceIndex_Hmd);',
            '        const auto hmd_provider_session = hmd_provider_.BeginSample();\n'
            '        vr::PropertyContainerHandle_t hmd_prop_container = vr::VRProperties()->TrackedDeviceToPropertyContainer(vr::k_unTrackedDeviceIndex_Hmd);')
    replace(driver, '    logger_->Log("Activating SlimeVR Driver...");',
            '    logger_->Log("Activating SlimeVR Driver...");\n'
            '    if (monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE")) {\n'
            '        try {\n'
            '            hmd_frame_probe_ = std::make_unique<monaka::HmdFrameProbeDiagnostics>(\n'
            '                monaka::NewProbeOwner(), monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE_POSES"),\n'
            '                monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE_BINDINGS"));\n'
            '        } catch (const std::exception& e) {\n'
            '            logger_->Log("MONAKA_FRAME_PROBE_V1 disabled: initialization failed: {}", e.what());\n'
            '        }\n'
            '    }')
    replace(driver, '        vr::VRServerDriverHost()->GetRawTrackedDevicePoses(0.0f, poses, std::size(poses));',
            '        vr::VRServerDriverHost()->GetRawTrackedDevicePoses(0.0f, poses, std::size(poses));\n'
            '        // Iteration-local software marker, not an acquisition timestamp or frame epoch.\n'
            '        std::optional<monaka::HmdRawPoseCaptureMarker> hmd_capture;\n'
            '        if (hmd_frame_probe_) hmd_capture.emplace(hmd_frame_probe_->CaptureRawPose());')
    replace(driver, '        vr::ETrackedPropertyError universe_error;',
            '        monaka::FrameProbeObservation hmd_probe_input;\n'
            '        bool hmd_probe_position_observed = false;\n'
            '        auto probe_lookup = monaka::TransformLookupState::UniverseUnavailable;\n'
            '        vr::ETrackedPropertyError universe_error;')
    replace(driver, '        if (universe_error == vr::ETrackedPropertyError::TrackedProp_Success) {',
            '        if (universe_error == vr::ETrackedPropertyError::TrackedProp_Success) {\n'
            '            if (hmd_frame_probe_) hmd_probe_input.universeId = universe;\n'
            '            probe_lookup = monaka::TransformLookupState::CachedForObservedUniverse;')
    replace(driver, '                auto result = SearchUniverses(universe);',
            '                auto result = SearchUniverses(universe);\n'
            '                probe_lookup = result.has_value() ? monaka::TransformLookupState::RefreshSucceeded\n'
            '                                                  : monaka::TransformLookupState::RefreshFailed;')
    replace(driver, '        last_universe_error_ = universe_error;',
            '        last_universe_error_ = universe_error;\n'
            '        if (hmd_frame_probe_) {\n'
            '            hmd_probe_input.lookup = probe_lookup;\n'
            '            const auto& hmd = poses[vr::k_unTrackedDeviceIndex_Hmd];\n'
            '            monaka::CopyHmdTrackingObservation(hmd_probe_input, hmd);\n'
            '            if (current_universe_) {\n'
            '                const auto& trans = current_universe_->second;\n'
            '                hmd_probe_input.appliedCacheUniverseId = current_universe_->first;\n'
            '                hmd_probe_input.appliedTransform = monaka::AppliedUniverseTransform{\n'
            '                    trans.translation.v[0], trans.translation.v[1], trans.translation.v[2], trans.yaw};\n'
            '            }\n'
            '        }')
    replace(driver, '                vr::HmdVector3_t pos = GetPosition(pose.mDeviceToAbsoluteTracking);',
            '                vr::HmdVector3_t pos = GetPosition(pose.mDeviceToAbsoluteTracking);\n'
            '                std::optional<monaka::HmdDiagnosticPose> hmd_raw_diagnostic;\n'
            '                if (index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                    hmd_raw_diagnostic.emplace(monaka::HmdDiagnosticPose{\n'
            '                        pos.v[0], pos.v[1], pos.v[2], (float)q.x, (float)q.y, (float)q.z, (float)q.w});\n'
            '                }')
    replace(driver, '                    auto trans = current_universe_.value().second;',
            '                    auto trans = current_universe_.value().second;\n'
            '                    if (hmd_frame_probe_ && index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                        // Copy the very value used by the unchanged conversion below.\n'
            '                        hmd_probe_input.appliedCacheUniverseId = current_universe_->first;\n'
            '                        hmd_probe_input.appliedTransform = monaka::AppliedUniverseTransform{\n'
            '                            trans.translation.v[0], trans.translation.v[1], trans.translation.v[2], trans.yaw};\n'
            '                    }')
    # Keep every expression/cast in the same order; diagnostics and setters share these exact floats.
    for field, expression in (("x", "pos.v[0]"), ("y", "pos.v[1]"), ("z", "pos.v[2]"),
                              ("qx", "(float)q.x"), ("qy", "(float)q.y"), ("qz", "(float)q.z"), ("qw", "(float)q.w")):
        replace(driver, f'                position->set_{field}({expression});',
                f'                const float wire_{field} = {expression};\n'
                f'                position->set_{field}(wire_{field});')
    replace(driver, '                position->set_qw(wire_qw);\n                bridge_->SendBridgeMessage(*message);',
            '                position->set_qw(wire_qw);\n'
            '                // Freeze one software observation from this iteration and the exact final send locals.\n'
            '                // No transport/admission claim; stale lifecycle tickets fail closed without altering sends.\n'
            '                std::optional<monaka::RawHmdProviderEvidenceSnapshot> hmd_provider_evidence;\n'
            '                if (index == vr::k_unTrackedDeviceIndex_Hmd && hmd_provider_session) {\n'
            '                    const auto& raw = *hmd_raw_diagnostic;\n'
            '                    const auto captured = hmd_provider_.Capture(*hmd_provider_session,\n'
            '                        monaka::ProviderHmdPoseSample{\n'
            '                            {raw.px, raw.py, raw.pz, raw.qx, raw.qy, raw.qz, raw.qw},\n'
            '                            {wire_x, wire_y, wire_z, wire_qx, wire_qy, wire_qz, wire_qw},\n'
            '                            static_cast<int32_t>(position->data_source()), pose.bPoseIsValid,\n'
            '                            pose.bDeviceIsConnected, static_cast<int32_t>(pose.eTrackingResult)});\n'
            '                    if (captured) hmd_provider_evidence.emplace(*captured);\n'
            '                }\n'
            '                if (hmd_frame_probe_ && index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                    hmd_frame_probe_->BindAndSend(*hmd_capture, hmd_probe_input, *hmd_raw_diagnostic,\n'
            '                        monaka::HmdDiagnosticPose{wire_x, wire_y, wire_z, wire_qx, wire_qy, wire_qz, wire_qw},\n'
            '                        static_cast<int32_t>(position->data_source()), *logger_,\n'
            '                        [&] { bridge_->SendBridgeMessage(*message); });\n'
            '                    hmd_probe_position_observed = true;\n'
            '                } else {\n'
            '                    bridge_->SendBridgeMessage(*message);\n'
            '                }')
    replace(driver, '        arena_.Reset();\n\n        std::this_thread::sleep_for(std::chrono::milliseconds(2));',
            '        if (hmd_frame_probe_ && !hmd_probe_position_observed) {\n'
            '            // Availability is observed even when the existing path sends no HMD Position.\n'
            '            hmd_frame_probe_->ObservePose(hmd_probe_input, false, *logger_);\n'
            '        }\n'
            '        arena_.Reset();\n\n        std::this_thread::sleep_for(std::chrono::milliseconds(2));')
    replace(driver, '        events.push_back(event);',
            '        events.push_back(event);\n'
            '        // Tap the existing pump; preserve its full event vector and haptic consumption.\n'
            '        if (hmd_frame_probe_) hmd_frame_probe_->ObserveEvent(event.eventType, *logger_);')
    replace(driver, 'if (message.has_tracker_added()) {',
            'if (message.has_user_action() && message.user_action().name() == "monaka-direct-output-v1?") {\n'
            '        messages::ProtobufMessage reply;\n'
            '        *reply.mutable_user_action() = message.user_action();\n'
            '        reply.mutable_user_action()->set_name("monaka-direct-output-v1");\n'
            '        bridge_->SendBridgeMessage(reply);\n'
            '    } else if (message.has_tracker_added()) {')
    with (output / "CMakeLists.txt").open("a", encoding="utf-8") as f:
        f.write('\n# Monaka Direct output contract, software-only; no runtime installation.\n'
                'enable_testing()\nadd_executable(monaka_direct_pose_test direct_pose_test.cpp)\n'
                'target_link_libraries(monaka_direct_pose_test PRIVATE SlimeVR-OpenVR-Driver_static)\n'
                'add_test(NAME monaka_direct_pose COMMAND monaka_direct_pose_test)\n')
        f.write('\n# Driver-local observations only; never a trusted frame epoch.\n'
                'add_executable(monaka_hmd_frame_probe_test frame_probe_test.cpp)\n'
                'target_include_directories(monaka_hmd_frame_probe_test PRIVATE ${DEPS_INCLUDES})\n'
                'set_target_properties(monaka_hmd_frame_probe_test PROPERTIES CXX_STANDARD 20)\n'
                'add_test(NAME monaka_hmd_frame_probe COMMAND monaka_hmd_frame_probe_test)\n')
        f.write('\nadd_executable(monaka_hmd_pose_binding_test pose_frame_binding_test.cpp)\n'
                'target_link_libraries(monaka_hmd_pose_binding_test PRIVATE SlimeVR-OpenVR-Driver_static)\n'
                'set_target_properties(monaka_hmd_pose_binding_test PROPERTIES CXX_STANDARD 20)\n'
                'add_test(NAME monaka_hmd_pose_binding COMMAND monaka_hmd_pose_binding_test)\n'
                'set_tests_properties(monaka_hmd_pose_binding PROPERTIES TIMEOUT 30)\n')
        f.write('\n# Provider-owned software observations; no Strong Trusted admission or transport.\n'
                'add_executable(monaka_raw_hmd_provider_evidence_test raw_hmd_provider_evidence_test.cpp)\n'
                'target_include_directories(monaka_raw_hmd_provider_evidence_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)\n'
                'set_target_properties(monaka_raw_hmd_provider_evidence_test PROPERTIES CXX_STANDARD 20)\n'
                'add_test(NAME monaka_raw_hmd_provider_evidence COMMAND monaka_raw_hmd_provider_evidence_test)\n'
                'set_tests_properties(monaka_raw_hmd_provider_evidence PROPERTIES TIMEOUT 30)\n'
                'add_executable(monaka_raw_hmd_provider_integration_test raw_hmd_provider_integration_test.cpp)\n'
                'target_link_libraries(monaka_raw_hmd_provider_integration_test PRIVATE SlimeVR-OpenVR-Driver_static)\n'
                'set_target_properties(monaka_raw_hmd_provider_integration_test PROPERTIES CXX_STANDARD 20)\n'
                'add_test(NAME monaka_raw_hmd_provider_integration COMMAND monaka_raw_hmd_provider_integration_test)\n'
                'set_tests_properties(monaka_raw_hmd_provider_integration PROPERTIES TIMEOUT 30)\n'
                'if(WIN32)\n'
                '  target_link_libraries(SlimeVR-OpenVR-Driver_static PUBLIC bcrypt)\n'
                'endif()\n')
    (output / "monaka-overlay.json").write_text(json.dumps({"upstream_commit": PIN, "submodules": modules}, indent=2), encoding="utf-8")
    print(f"PASS prepared Direct driver overlay: {output}")


if __name__ == "__main__":
    main()
