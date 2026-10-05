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
            '#include "Logger.hpp"\n#include "MonakaHmdFrameProbeDriver.hpp"')
    replace(driver_header, '    std::optional<std::pair<uint64_t, UniverseTranslation>> current_universe_ = std::nullopt;',
            '    std::optional<std::pair<uint64_t, UniverseTranslation>> current_universe_ = std::nullopt;\n'
            '    // Created before worker startup and never reassigned during driver lifetime. Default OFF.\n'
            '    std::unique_ptr<monaka::HmdFrameProbeDiagnostics> hmd_frame_probe_;')
    replace(driver, '    logger_->Log("Activating SlimeVR Driver...");',
            '    logger_->Log("Activating SlimeVR Driver...");\n'
            '    if (monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE")) {\n'
            '        try {\n'
            '            hmd_frame_probe_ = std::make_unique<monaka::HmdFrameProbeDiagnostics>(\n'
            '                monaka::NewProbeOwner(), monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE_POSES"));\n'
            '        } catch (const std::exception& e) {\n'
            '            logger_->Log("MONAKA_FRAME_PROBE_V1 disabled: initialization failed: {}", e.what());\n'
            '        }\n'
            '    }')
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
    replace(driver, '                    auto trans = current_universe_.value().second;',
            '                    auto trans = current_universe_.value().second;\n'
            '                    if (hmd_frame_probe_ && index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                        // Copy the very value used by the unchanged conversion below.\n'
            '                        hmd_probe_input.appliedCacheUniverseId = current_universe_->first;\n'
            '                        hmd_probe_input.appliedTransform = monaka::AppliedUniverseTransform{\n'
            '                            trans.translation.v[0], trans.translation.v[1], trans.translation.v[2], trans.yaw};\n'
            '                    }')
    replace(driver, '                position->set_qw((float)q.w);\n                bridge_->SendBridgeMessage(*message);',
            '                position->set_qw((float)q.w);\n'
            '                if (hmd_frame_probe_ && index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                    hmd_frame_probe_->ObservePose(hmd_probe_input, true, *logger_);\n'
            '                    hmd_probe_position_observed = true;\n'
            '                }\n'
            '                bridge_->SendBridgeMessage(*message);')
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
    (output / "monaka-overlay.json").write_text(json.dumps({"upstream_commit": PIN, "submodules": modules}, indent=2), encoding="utf-8")
    print(f"PASS prepared Direct driver overlay: {output}")


if __name__ == "__main__":
    main()
