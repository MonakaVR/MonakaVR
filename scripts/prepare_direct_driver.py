"""Prepare an isolated, pinned SlimeVR driver overlay. Never installs or edits the source checkout."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess

PIN = "dcc0f56bcb2a3196d6f92b1ed1d029faa425b931"
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
    (output / "monaka-overlay.json").write_text(json.dumps({"upstream_commit": PIN, "submodules": modules}, indent=2), encoding="utf-8")
    print(f"PASS prepared Direct driver overlay: {output}")


if __name__ == "__main__":
    main()
