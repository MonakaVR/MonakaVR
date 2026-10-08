"""Verify preservation of pinned runtime paths in a freshly prepared probe overlay."""
import argparse
import hashlib
from pathlib import Path


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def function(text, name):
    start = text.index(name)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--overlay", type=Path, required=True)
    args = parser.parse_args()
    source = (args.source / "src/VRDriver.cpp").read_text(encoding="utf-8")
    overlay = (args.overlay / "src/VRDriver.cpp").read_text(encoding="utf-8")
    # The provider tripwire verifies these exact additions before normalizing them
    # away for the older frame/binding preservation checks below.
    from test_raw_hmd_provider_overlay import verify_provider
    overlay = verify_provider(args.source, args.overlay, overlay)
    # The only setter refactor allowed is naming the same binary32 value once.
    normalized = overlay
    for field, expression in (("x", "pos.v[0]"), ("y", "pos.v[1]"), ("z", "pos.v[2]"),
                              ("qx", "(float)q.x"), ("qy", "(float)q.y"), ("qz", "(float)q.z"), ("qw", "(float)q.w")):
        named = (f'                const float wire_{field} = {expression};\n'
                 f'                position->set_{field}(wire_{field});')
        require(overlay.count(named) == 1, f"Wire expression/cast changed: {field}")
        normalized = normalized.replace(named, f'                position->set_{field}({expression});')
    capture = ('        // Iteration-local software marker, not an acquisition timestamp or frame epoch.\n'
               '        std::optional<monaka::HmdRawPoseCaptureMarker> hmd_capture;\n'
               '        if (hmd_frame_probe_) hmd_capture.emplace(hmd_frame_probe_->CaptureRawPose());')
    require(overlay.count(capture) == 1 and
            'GetRawTrackedDevicePoses(0.0f, poses, std::size(poses));\n' + capture in overlay,
            "Marker must immediately follow the single existing raw query")
    normalized = normalized.replace('\n' + capture, '')
    for name in ("void SlimeVRDriver::VRDriver::OnBridgeConnect()",
                 "vr::HmdQuaternion_t SlimeVRDriver::VRDriver::GetRotation",
                 "vr::HmdVector3_t SlimeVRDriver::VRDriver::GetPosition",
                 "SlimeVRDriver::UniverseTranslation SlimeVRDriver::UniverseTranslation::parse",
                 "std::optional<SlimeVRDriver::UniverseTranslation> SlimeVRDriver::VRDriver::SearchUniverse(",
                 "std::optional<SlimeVRDriver::UniverseTranslation> SlimeVRDriver::VRDriver::SearchUniverses(",
                 "std::optional<SlimeVRDriver::UniverseTranslation> SlimeVRDriver::VRDriver::GetCurrentUniverse()"):
        require(function(source, name) == function(overlay, name), f"Changed pinned method: {name}")
    for start, end in (("                    pos.v[0] += trans.translation.v[0];", "                messages::Position* position"),
                       ("                messages::Position* position", "                position->set_qw((float)q.w);"),
                       ("        if (!bridge_->IsConnected()) {", "        vr::PropertyContainerHandle_t hmd_prop_container")):
        block = source[source.index(start):source.index(end) + len(end)]
        require(block in normalized, f"Changed pinned block: {start}")
    for call in ("PollNextEvent(", "GetRawTrackedDevicePoses(", "SearchUniverses(universe)",
                 "current_universe_.emplace(", "Prop_CurrentUniverseId_Uint64, &universe_error"):
        require(source.count(call) == overlay.count(call), f"Added/removed runtime call: {call}")
    cache_guard = "if (!current_universe_.has_value() || current_universe_.value().first != universe)"
    require(overlay.count(cache_guard) == 1, "Same-ID cache-refresh guard changed")
    eligible = 'if (pose.bPoseIsValid || pose.eTrackingResult == vr::TrackingResult_Fallback_RotationOnly)'
    require(source.count(eligible) == overlay.count(eligible) == 1, "Original Position/no-send eligibility changed")
    require(overlay.count("events.push_back(event);") == 1 and overlay.count("openvr_events_ = std::move(events);") == 1,
            "Original event handoff changed")
    require("events.push_back(event);\n        // Tap the existing pump" in overlay, "Event hook location changed")
    send = ('                if (hmd_frame_probe_ && index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
            '                    hmd_frame_probe_->BindAndSend(*hmd_capture, hmd_probe_input, *hmd_raw_diagnostic,\n'
            '                        monaka::HmdDiagnosticPose{wire_x, wire_y, wire_z, wire_qx, wire_qy, wire_qz, wire_qw},\n'
            '                        static_cast<int32_t>(position->data_source()), *logger_,\n'
            '                        [&] { bridge_->SendBridgeMessage(*message); });\n'
            '                    hmd_probe_position_observed = true;\n'
            '                } else {\n'
            '                    bridge_->SendBridgeMessage(*message);\n'
            '                }')
    require(overlay.count(send) == 1 and 'position->set_qw(wire_qw);\n' + send in overlay,
            "Enabled callback/off/non-HMD direct send must each reach the original bridge once")
    require(normalized.replace(send, '                bridge_->SendBridgeMessage(*message);').count(
        'bridge_->SendBridgeMessage(*message);') == source.count('bridge_->SendBridgeMessage(*message);'),
        "Non-position message sends changed")
    raw = ('                vr::HmdQuaternion_t q = GetRotation(pose.mDeviceToAbsoluteTracking);\n'
           '                vr::HmdVector3_t pos = GetPosition(pose.mDeviceToAbsoluteTracking);\n'
           '                std::optional<monaka::HmdDiagnosticPose> hmd_raw_diagnostic;\n'
           '                if (index == vr::k_unTrackedDeviceIndex_Hmd) {\n'
           '                    hmd_raw_diagnostic.emplace(monaka::HmdDiagnosticPose{\n'
           '                        pos.v[0], pos.v[1], pos.v[2], (float)q.x, (float)q.y, (float)q.z, (float)q.w});\n'
           '                }\n\n                if (current_universe_.has_value())')
    require(overlay.count(raw) == 1, "Raw diagnostic must copy the existing conversion before universe transform")
    require(source.count('GetRotation(pose.mDeviceToAbsoluteTracking)') ==
            overlay.count('GetRotation(pose.mDeviceToAbsoluteTracking)') and
            source.count('GetPosition(pose.mDeviceToAbsoluteTracking)') ==
            overlay.count('GetPosition(pose.mDeviceToAbsoluteTracking)'), "Diagnostic pose was recomputed")
    adapter = (args.overlay / 'src/MonakaHmdFrameProbeDriver.hpp').read_text(encoding='utf-8')
    bind = function(adapter, 'HmdObservedPoseFrameBinding BindAndSend(')
    require(bind.count('std::lock_guard<std::mutex> lock(mutex_);') == 1 and
            bind.count('std::forward<Send>(send)();') == 1 and
            bind.index('lock(mutex_)') < bind.index('probe_.BindPose(') < bind.index('std::forward<Send>(send)();'),
            "Final binding and actual send must remain in the same mutex scope")
    event = function(adapter, 'void ObserveEvent(')
    require(event.count('std::lock_guard<std::mutex> lock(mutex_);') == 1 and
            event.index('lock(mutex_)') < event.index('probe_.ObserveSignal('),
            "Mapped event mutation must share the bind/send mutex")
    init = function(overlay, 'vr::EVRInitError SlimeVRDriver::VRDriver::Init(')
    require(init.count('MONAKA_HMD_FRAME_PROBE_BINDINGS') == 1 and
            overlay.count('MONAKA_HMD_FRAME_PROBE_BINDINGS') == 1 and
            init.count('if (monaka::ProbeEnvironmentEnabled("MONAKA_HMD_FRAME_PROBE"))') == 1,
            "Separate trace flag must be sampled once under main opt-in at initialization")
    require("VRSystem()" not in overlay and "VRChaperone()" not in overlay, "Unsupported client runtime query")
    from monaka_bridge_protocol_overlay import patched_schema
    require((args.overlay / 'src/bridge/ProtobufMessages.proto').read_text(encoding='utf-8') ==
            patched_schema((args.source / 'src/bridge/ProtobufMessages.proto').read_text(encoding='utf-8')),
            "Schema must use single deterministic patch authority")
    for name in (
                 "src/bridge/BridgeTransport.cpp", "src/bridge/BridgeTransport.hpp",
                 "src/bridge/CircularBuffer.cpp", "src/bridge/CircularBuffer.hpp", "src/TrackerDevice.hpp", "src/Logger.hpp"):
        require(hashlib.sha256((args.source / name).read_bytes()).digest() ==
                hashlib.sha256((args.overlay / name).read_bytes()).digest(), f"Unrelated/wire source changed: {name}")
    print("PASS pinned transform/lookup/cache cadence/transport/event handoff/wire preservation")


if __name__ == "__main__":
    main()
