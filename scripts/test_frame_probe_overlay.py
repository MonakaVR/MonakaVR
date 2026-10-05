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
        require(block in overlay, f"Changed pinned block: {start}")
    for call in ("PollNextEvent(", "GetRawTrackedDevicePoses(", "SearchUniverses(universe)",
                 "current_universe_.emplace(", "Prop_CurrentUniverseId_Uint64, &universe_error"):
        require(source.count(call) == overlay.count(call), f"Added/removed runtime call: {call}")
    cache_guard = "if (!current_universe_.has_value() || current_universe_.value().first != universe)"
    require(overlay.count(cache_guard) == 1, "Same-ID cache-refresh guard changed")
    require(overlay.count("events.push_back(event);") == 1 and overlay.count("openvr_events_ = std::move(events);") == 1,
            "Original event handoff changed")
    require("events.push_back(event);\n        // Tap the existing pump" in overlay, "Event hook location changed")
    require("hmd_frame_probe_->ObservePose(hmd_probe_input, true, *logger_);\n"
            "                    hmd_probe_position_observed = true;\n"
            "                }\n                bridge_->SendBridgeMessage(*message);" in overlay,
            "Pose observation no longer immediately precedes original send")
    require("VRSystem()" not in overlay and "VRChaperone()" not in overlay, "Unsupported client runtime query")
    for name in ("src/bridge/ProtobufMessages.proto", "src/bridge/BridgeClient.cpp",
                 "src/bridge/BridgeTransport.cpp", "src/TrackerDevice.hpp", "src/Logger.hpp"):
        require(hashlib.sha256((args.source / name).read_bytes()).digest() ==
                hashlib.sha256((args.overlay / name).read_bytes()).digest(), f"Unrelated/wire source changed: {name}")
    print("PASS pinned transform/lookup/cache cadence/transport/event handoff/wire preservation")


if __name__ == "__main__":
    main()
