"""Single deterministic authority for the pinned bridge schema extension."""
import hashlib
from pathlib import Path

PIN = "dcc0f56bcb2a3196d6f92b1ed1d029faa425b931"
ORIGINAL_SHA256 = "0ce72ac1dd484c3d3c4d93f350d55a4880b6c74c29a3028a580bf77682b71c59"
EVIDENCE_MESSAGE = '''message HmdProviderSampleEvidenceV1 {
    optional string provider_session_epoch = 1;
    optional uint64 observation_id = 2;
    optional float raw_x = 3;
    optional float raw_y = 4;
    optional float raw_z = 5;
    optional float raw_qx = 6;
    optional float raw_qy = 7;
    optional float raw_qz = 8;
    optional float raw_qw = 9;
    optional float wire_x = 10;
    optional float wire_y = 11;
    optional float wire_z = 12;
    optional float wire_qx = 13;
    optional float wire_qy = 14;
    optional float wire_qz = 15;
    optional float wire_qw = 16;
    optional int32 data_source = 17;
    optional bool pose_valid = 18;
    optional bool device_connected = 19;
    optional int32 tracking_result = 20;
}

'''


def patched_schema(original: str) -> str:
    original = original.replace("\r\n", "\n")
    if hashlib.sha256(original.encode("utf-8")).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Pinned original bridge schema mismatch")
    anchor = "    optional float vz = 12;\n}"
    if original.count(anchor) != 1 or original.count("message Position {") != 1:
        raise ValueError("Pinned schema anchors mismatch")
    return original.replace(anchor, "    optional float vz = 12;\n"
        "    optional HmdProviderSampleEvidenceV1 hmd_provider_evidence_v1 = 13;\n}").replace(
        "message Position {", EVIDENCE_MESSAGE + "message Position {")


def write_patched_schema(source: Path, output: Path):
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(patched_schema(source.read_text(encoding="utf-8")).encode("utf-8"))
