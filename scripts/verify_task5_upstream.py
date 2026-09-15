"""Accept only actual Task4 acc329d artifacts and the supplied fixed Task1 kit."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HEAD = "acc329dce90dd6ba21387029cd54b6fc2d82fe8c"
ZIP_SHA = "3fe2b3c1b1703520e5433639647c25894a64432590997275da506c262f647cd3"
MANIFEST_SHA = "c2bc55d0335d207e86a842b8d9a2ac687ce5a65414d3dfab884e63cbd23c7dc7"
KIT_SHA = "eef5b7f2bc490926385b99dabcd44dc5a374228bf2a7869beea01f9dad936729"
LOCK_SHA = "234f0dffc46b808a179ec91da3c185794d7b7c83bc5b7d2bcb31fa73886bcb44"
C1_SHA = "3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        require(path.read_bytes() == data, f"Existing artifact differs: {path}")
    else:
        path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--supply", type=Path)
    args = parser.parse_args()
    dependency_dir = ROOT / "dependencies/task4"
    if args.supply:
        for filename in (
            "monaka-bridge-handoff.zip",
            "monaka-bridge-handoff.handoff.json",
            "task4-final-report.json",
        ):
            source = args.supply / filename
            require(source.is_file(), f"Missing supplied Task4 artifact: {source}")
            write(dependency_dir / filename, source.read_bytes())

    archive_path = dependency_dir / "monaka-bridge-handoff.zip"
    external_path = dependency_dir / "monaka-bridge-handoff.handoff.json"
    report_path = dependency_dir / "task4-final-report.json"
    for path in (archive_path, external_path, report_path):
        require(path.is_file(), f"Missing Task4 artifact: {path}")

    archive_bytes = archive_path.read_bytes()
    external_bytes = external_path.read_bytes()
    report = json.loads(report_path.read_bytes())
    require(sha(archive_bytes) == ZIP_SHA, "Task4 handoff ZIP SHA256 mismatch")
    require(sha(external_bytes) == MANIFEST_SHA, "Task4 external manifest SHA256 mismatch")

    metadata = json.loads(external_bytes)
    require(metadata.get("source_commit") == HEAD, "Task4 external source_commit mismatch")
    require(metadata.get("artifact", {}).get("source_commit") == HEAD, "Task4 artifact source_commit mismatch")
    require(report.get("HEAD_SHA") == HEAD, "Task4 report HEAD mismatch")
    require(metadata.get("artifact", {}).get("sha256") == ZIP_SHA, "Task4 manifest artifact hash mismatch")
    handoff_artifacts = report.get("handoff_artifacts", [])
    require(
        any(
            entry.get("filename") == "monaka-bridge-handoff.zip"
            and entry.get("sha256") == ZIP_SHA
            and entry.get("source_commit") == HEAD
            for entry in handoff_artifacts
        ),
        "Task4 report lacks the accepted ZIP entry",
    )
    require(
        any(
            entry.get("filename") == "monaka-bridge-handoff.handoff.json"
            and entry.get("sha256") == MANIFEST_SHA
            for entry in handoff_artifacts
        ),
        "Task4 report lacks the accepted external manifest entry",
    )

    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        bad_member = archive.testzip()
        require(bad_member is None, f"Task4 ZIP integrity failure: {bad_member}")
        content_bytes = archive.read("handoff-content-manifest.json")
        require(
            sha(content_bytes) == metadata.get("content_manifest_sha256"),
            "Task4 content manifest SHA256 mismatch",
        )
        content = json.loads(content_bytes)
        require(content.get("source_commit") == HEAD, "Task4 content manifest source_commit mismatch")
        content_files = content.get("files")
        require(isinstance(content_files, list), "Task4 content manifest files must be a list")
        for entry in content_files:
            member = entry.get("path")
            require(isinstance(member, str), "Task4 content entry path is invalid")
            require(sha(archive.read(member)) == entry.get("sha256"), f"Task4 content hash mismatch: {member}")

        validation_bytes = archive.read("evidence/validation-results.json")
        validation = json.loads(validation_bytes)
        require(validation.get("status") == "PASS", "Task4 validation status is not PASS")
        checks = validation.get("checks", {})
        for name in ("direct-standalone", "ctest", "separate-process-udp", "fixed-codec-interop"):
            require(checks.get(name, {}).get("result") == "PASS", f"Task4 validation check is not PASS: {name}")

        origins = json.loads(archive.read("source/dependencies/task2-derived-files.json"))
        origin_files = origins.get("files")
        require(isinstance(origin_files, list), "Task2-derived provenance files must be a list")
        for entry in origin_files:
            member = "source/" + entry.get("path", "")
            require(sha(archive.read(member)) == entry.get("sha256"), f"Task2-derived hash mismatch: {member}")

        kit_bytes = archive.read("source/dependencies/artifacts/monaka-protocol-kit-v1.0.zip")
        require(sha(kit_bytes) == KIT_SHA, "Embedded Task1 protocol kit SHA256 mismatch")
        write(ROOT / "dependencies/monaka-protocol-kit-v1.0.zip", kit_bytes)
        task1_metadata = archive.read("source/dependencies/reports/handoff-manifest.json")
        write(ROOT / "dependencies/task1-handoff-manifest.json", task1_metadata)
        write(dependency_dir / "verified-content-manifest.json", content_bytes)
        write(dependency_dir / "verified-validation-results.json", validation_bytes)

    with zipfile.ZipFile(io.BytesIO(kit_bytes)) as kit:
        bad_member = kit.testzip()
        require(bad_member is None, f"Task1 kit ZIP integrity failure: {bad_member}")
        protocol_lock = kit.read("protocol.lock.json")
        c1 = kit.read("docs/C1.md")
        require(sha(protocol_lock) == LOCK_SHA, "Task1 protocol.lock.json SHA256 mismatch")
        require(sha(c1) == C1_SHA, "Task1 C1 SHA256 mismatch")
        write(ROOT / "dependencies/monaka-protocol.lock.json", protocol_lock)
        for line in kit.read("SHA256SUMS").decode().splitlines():
            parts = line.split(maxsplit=1)
            require(len(parts) == 2, f"Malformed Task1 SHA256SUMS line: {line!r}")
            digest, member = parts
            member = member.lstrip("*")
            require(sha(kit.read(member)) == digest, f"Task1 kit member hash mismatch: {member}")
        for name in kit.namelist():
            if name.endswith("/"):
                continue
            relative = Path(name)
            require(not relative.is_absolute() and ".." not in relative.parts, f"Unsafe Task1 kit path: {name}")
            write(ROOT / "third_party/monaka-protocol" / relative, kit.read(name))

    acceptance = {
        "task4_source_commit": HEAD,
        "task4_handoff_sha256": ZIP_SHA,
        "task4_external_manifest_sha256": MANIFEST_SHA,
        "task4_report_sha256": sha(report_path.read_bytes()),
        "task4_content_files_verified": len(content_files),
        "task1_kit_sha256": KIT_SHA,
        "protocol_lock_sha256": LOCK_SHA,
        "c1_sha256": C1_SHA,
        "hardware": "NOT RUN",
    }
    write(ROOT / "dependencies/task5-upstream.lock.json", (json.dumps(acceptance, indent=2) + "\n").encode())
    print("PASS actual Task4 accepted HEAD/ZIP/manifest/report/219 contents/validation; exact Task1 kit/internal sums/lock/C1")


if __name__ == "__main__":
    main()
