"""Generate server Java from the same schema patch as the driver overlay."""
import argparse
from pathlib import Path
import subprocess
from monaka_bridge_protocol_overlay import PIN, write_patched_schema
from prepare_direct_driver import git, require


def generate(source: Path, protoc: Path, output: Path):
    require(git(source, "rev-parse", "HEAD") == PIN, "Upstream HEAD mismatch")
    require(not git(source, "status", "--porcelain"), "Upstream must be clean")
    require(subprocess.check_output([str(protoc), "--version"], text=True).strip() == "libprotoc 31.1",
            "protoc must be exactly libprotoc 31.1")
    schema = output / "schema/ProtobufMessages.proto"
    write_patched_schema(source / "src/bridge/ProtobufMessages.proto", schema)
    java = output / "java"
    java.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(protoc), "--proto_path=" + str(schema.parent), "--java_out=" + str(java),
                    str(schema)], check=True)
    return java / "dev/slimevr/desktop/platform/ProtobufMessages.java"


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--protoc", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--check", type=Path)
    p.add_argument("--install", type=Path)
    a = p.parse_args()
    generated = generate(a.source.resolve(), a.protoc.resolve(), a.output.resolve())
    if a.check:
        require(generated.read_bytes() == a.check.read_bytes(), "Generated Java is not byte-identical")
    if a.install:
        a.install.write_bytes(generated.read_bytes())
    print("PASS deterministic schema/Java generation (libprotoc 31.1)")
