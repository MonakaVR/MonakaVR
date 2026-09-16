"""F10 source-bound software release evidence. No runtime or sibling-repo writes.

The identical helper is committed in each repository so a checkout is standalone.
Repository-specific commands/artifacts live in release_v2.json. Hardware is never inferred.
"""
import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WIRE = {"major": 2, "minor": 0}


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded(value))


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").strip()


def member(name):
    path = PurePosixPath(name)
    require(bool(name) and not path.is_absolute() and ".." not in path.parts
            and "\\" not in name and ":" not in name, "Unsafe member: " + name)
    require(path.as_posix() == name, "Noncanonical member: " + name)
    return name


def snapshot():
    require(not git("status", "--porcelain", "--untracked-files=all"), "Release requires a clean committed tree")
    files = git("ls-files", "--recurse-submodules").splitlines()
    hashes = {n: sha((ROOT / n).read_bytes()) for n in files if (ROOT / n).is_file()}
    require(hashes, "Empty source snapshot")
    return {"commit": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}"),
            "branch": git("branch", "--show-current"), "submodules": git("submodule", "status", "--recursive"),
            "files_sha256": hashes}


def check_tests(paths, required):
    cases = []
    for path in paths:
        node = ET.parse(path).getroot()
        for suite in ([node] if node.tag == "testsuite" else node.findall("testsuite")):
            require(int(suite.get("tests", "0")) > 0, "Empty test suite: " + str(path))
            for counter in ("failures", "errors", "skipped"):
                require(int(suite.get(counter, "0")) == 0, f"{counter} in {path}")
            found = suite.findall("testcase")
            require(len(found) == int(suite.get("tests")), "Test count mismatch: " + str(path))
            for case in found:
                require(not any(case.find(x) is not None for x in ("failure", "error", "skipped")),
                        "Unsuccessful testcase: " + str(path))
                require(case.get("status", "run") not in ("notrun", "disabled"), "Test not run")
                cases.append(case.get("name"))
    require(cases, "No testcases executed")
    require(set(required) <= set(cases), "Missing required testcases: " + str(sorted(set(required) - set(cases))))
    return {"result": "PASS", "tests": len(cases), "required": required, "cases": cases}


def check_kit(archive_path, manifest_path, expected=None):
    return check_kit_bytes(archive_path.read_bytes(), manifest_path.read_bytes(), expected)


def check_kit_bytes(archive_bytes, raw, expected=None):
    metadata = json.loads(raw)
    require(metadata["wire_version"] == WIRE, "Not current wire v2")
    require(sha(archive_bytes) == metadata["sha256"], "Kit ZIP hash mismatch")
    if expected is not None:
        require(sha(raw) == expected["manifest_sha256"], "Pinned external manifest mismatch")
        for key in ("source_commit", "sha256", "schema_commit", "protocol_lock_sha256", "contract_revision_sha256", "wire_version"):
            require(metadata[key] == expected[key], "Pinned identity mismatch: " + key)
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), "Duplicate kit members")
        for name in names:
            member(name)
        require(z.testzip() is None, "Kit CRC failure")
        lock_bytes = z.read("protocol.lock.json")
        require(sha(lock_bytes) == metadata["protocol_lock_sha256"], "Kit lock mismatch")
        lock = json.loads(lock_bytes)
        require(lock["source_commit"] == metadata["source_commit"] and lock["source_tree_clean"] is True, "Kit source mismatch")
        require(lock["wire_version"] == WIRE, "Kit lock version mismatch")
        require(sha(z.read("docs/C2.md")) == metadata["contract_revision_sha256"] == lock["contract_revision_sha256"], "C2 mismatch")
        sums = {}
        for line in z.read("SHA256SUMS").decode().splitlines():
            digest, name = line.split("  ", 1)
            require(name not in sums, "Duplicate checksum entry")
            sums[member(name)] = digest
        require(set(sums) == set(names) - {"SHA256SUMS"}, "Incomplete kit checksums")
        for name, digest in sums.items():
            require(sha(z.read(name)) == digest, "Kit content mismatch: " + name)
        require(set(lock["files_sha256"]) == set(names) - {"SHA256SUMS", "protocol.lock.json"}, "Incomplete kit lock")
        for name, digest in lock["files_sha256"].items():
            require(sha(z.read(name)) == digest, "Kit locked content mismatch: " + name)
    return metadata


def check_receipt(report, current, required_steps, required_tests):
    require(report.get("result") == "PASS" and report.get("wire_version") == WIRE, "Release status/version mismatch")
    require(report.get("source") == current, "Stale release source/HEAD")
    require(report.get("hardware_validation") == "NOT RUN" and report.get("steamvr_interactive") == "NOT RUN", "Unsupported hardware claim")
    steps = report.get("steps", [])
    require([s["name"] for s in steps] == required_steps, "Incomplete/out-of-order release commands")
    for step in steps:
        require(step["exit_code"] == 0 and step["command"] and step["log_sha256"], "Unsuccessful release command")
    tests = report.get("tests", [])
    require(len(tests) == len(required_tests), "Missing test groups")
    for evidence, required in zip(tests, required_tests):
        require(evidence["result"] == "PASS" and evidence["tests"] > 0 and set(required) <= set(evidence["cases"]), "Required test evidence missing")
    require(report.get("artifacts") and report.get("files_sha256"), "Missing release artifacts/hashes")


def write_archive(path, entries, source_commit):
    entries = dict(entries)
    require("content-manifest.json" not in entries, "Reserved manifest member")
    manifest = {"source_commit": source_commit, "wire_version": WIRE,
                "files_sha256": {member(n): sha(b) for n, b in sorted(entries.items())}}
    entries["content-manifest.json"] = encoded(manifest)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(member(name), date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    return {"artifact": path.name, "sha256": sha(path.read_bytes()), "source_commit": source_commit,
            "wire_version": WIRE, "content_manifest_sha256": sha(entries["content-manifest.json"])}


def verify_archive(path, external, expected_head):
    require(external["source_commit"] == expected_head and external["wire_version"] == WIRE, "External release identity mismatch")
    require(sha(path.read_bytes()) == external["sha256"], "Release ZIP hash mismatch")
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), "Duplicate release member")
        for name in names:
            member(name)
        require(z.testzip() is None, "Release CRC failure")
        data = z.read("content-manifest.json")
        require(sha(data) == external["content_manifest_sha256"], "Content manifest hash mismatch")
        manifest = json.loads(data)
        require(manifest["source_commit"] == expected_head and manifest["wire_version"] == WIRE, "Internal release identity mismatch")
        hashes = manifest["files_sha256"]
        require(hashes and set(hashes) == set(names) - {"content-manifest.json"}, "Incomplete release manifest")
        for name, digest in hashes.items():
            require(sha(z.read(name)) == digest, "Release member hash mismatch: " + name)
        return {n: z.read(n) for n in hashes}


def verify_release(directory, policy):
    current = snapshot()
    external = json.loads((directory / "handoff.json").read_bytes())
    entries = verify_archive(directory / member(external["artifact"]), external, current["commit"])
    report = json.loads(entries["evidence/release.json"])
    require(entries["evidence/release.json"] == (directory / "release.json").read_bytes(), "External receipt differs from ZIP")
    check_receipt(report, current, policy["required_steps"], [t["required"] for t in policy["test_groups"]])
    pin = None
    if policy["repo"] != "MonakaProtocol":
        pinned_bytes = (ROOT / "dependencies/monaka-protocol-v2.lock.json").read_bytes()
        require(entries["protocol/pin.json"] == pinned_bytes, "Archived dependency pin differs from source")
        pin = json.loads(pinned_bytes)
    kit = check_kit_bytes(entries["protocol/kit.zip"], entries["protocol/handoff-manifest.json"], pin)
    require(kit == report["protocol_dependency"], "Protocol evidence identity mismatch")
    if pin is None:
        require(kit["source_commit"] == current["commit"], "Protocol kit source differs from release")
    require(set(report["artifacts"]) == {a["name"] for a in policy["artifacts"]}, "Wrong release artifact set")
    for group, reported in zip(policy["test_groups"], report["tests"]):
        prefix = "evidence/tests/" + group["name"] + "/"
        results = [io.BytesIO(data) for name, data in entries.items() if name.startswith(prefix)]
        actual = check_tests(results, group["required"])
        require(actual["tests"] == reported["tests"] and sorted(actual["cases"]) == sorted(reported["cases"]), "Archived JUnit differs from receipt")
    require(set(report["files_sha256"]) == set(entries) - {"evidence/release.json"}, "Incomplete receipt file hashes")
    for name, digest in report["files_sha256"].items():
        require(sha(entries[name]) == digest, "Receipt file hash mismatch")
    for name, digest in report["artifacts"].items():
        require(sha(entries[name]) == digest, "Artifact differs from validated bytes")
    for step in report["steps"]:
        require(sha(entries[step["log"]]) == step["log_sha256"], "Command log mismatch")
    require(snapshot() == current, "Source changed during verification")
    print("PASS current v2 release archive/receipt/source/artifact hashes")


def run(policy):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--gradle", default="gradle")
    parser.add_argument("--openvr-sdk", type=Path, default=ROOT / "build/openvr-sdk")
    parser.add_argument("--verify", type=Path, help="Recheck an existing release against this clean HEAD; does not build")
    args = parser.parse_args()
    if args.verify:
        verify_release(args.verify.resolve(), policy)
        return
    require(os.name == "nt", "F10 release profile is Windows x64; other platforms are NOT RUN")
    token = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    work = ROOT / "build/release-v2" / token
    work.mkdir(parents=True)
    latest = ROOT / "dist/release-v2/latest.json"
    status = {"result": "RUNNING", "run": token, "hardware_validation": "NOT RUN", "steamvr_interactive": "NOT RUN"}
    save(latest, status)
    try:
        source = snapshot()
        require(args.java_home and (args.java_home / "bin/java.exe").is_file(), "Provide JDK17 --java-home")
        cmake = shutil.which(args.cmake)
        require(cmake, "CMake not found")
        env = {k.upper(): v for k, v in os.environ.items()}
        env["JAVA_HOME"] = str(args.java_home)
        env["PATH"] = os.pathsep.join([str(args.java_home / "bin"), str(Path(cmake).parent), env.get("PATH", "")])
        # Historical Python test harnesses still use asserts. Always execute those
        # in normal mode, even when this explicit-validation gate uses python -O.
        env["PYTHONOPTIMIZE"] = "0"
        values = {"ROOT": str(ROOT), "BUILD": str(work / "native"), "WORK": str(work),
                  "CMAKE": cmake, "CTEST": str(Path(cmake).with_name("ctest.exe")),
                  "PYTHON": sys.executable, "JAVA": str(args.java_home / "bin/java.exe"),
                  "JAVAC": str(args.java_home / "bin/javac.exe"), "JAVA_HOME": str(args.java_home),
                  "GRADLE": args.gradle, "OPENVR": str(args.openvr_sdk.resolve())}
        def expand(text):
            return text.format_map(values)
        steps = []
        entries = {}
        for step in policy["commands"]:
            command = [expand(x) for x in step["command"]]
            log = work / (step["name"] + ".log")
            with log.open("wb") as output:
                completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output,
                                           stderr=subprocess.STDOUT, timeout=1800)
            name = "evidence/logs/" + log.name
            entries[name] = log.read_bytes()
            steps.append({"name": step["name"], "command": command, "exit_code": completed.returncode,
                          "log": name, "log_sha256": sha(entries[name])})
            require(completed.returncode == 0, f"{step['name']} failed ({completed.returncode}); see {log}")
            print("PASS " + step["name"], flush=True)
        tests = []
        for group in policy["test_groups"]:
            paths = []
            for pattern in group["paths"]:
                pattern_path = Path(expand(pattern))
                paths.extend(sorted(pattern_path.parent.glob(pattern_path.name)))
            tests.append(check_tests(paths, group["required"]))
            for i, path in enumerate(paths):
                entries[f"evidence/tests/{group['name']}/{i}-{path.name}"] = path.read_bytes()
        if policy["repo"] == "MonakaProtocol":
            metadata_path = ROOT / "dist/v2/handoff-manifest.json"
            metadata = json.loads(metadata_path.read_bytes())
            archive = metadata_path.parent / metadata["artifact"]
            kit = check_kit(archive, metadata_path)
            require(kit["source_commit"] == source["commit"], "New protocol kit not built at release HEAD")
        else:
            pin_path = ROOT / "dependencies/monaka-protocol-v2.lock.json"
            pin = json.loads(pin_path.read_bytes())
            archive, metadata_path = ROOT / pin["artifact_path"], ROOT / pin["manifest_path"]
            kit = check_kit(archive, metadata_path, pin)
            entries["protocol/pin.json"] = pin_path.read_bytes()
        entries["protocol/kit.zip"] = archive.read_bytes()
        entries["protocol/handoff-manifest.json"] = metadata_path.read_bytes()
        artifacts = {}
        for output in policy["artifacts"]:
            name, path = member(output["name"]), Path(expand(output["path"]))
            require(path.is_file() and path.stat().st_size > 0, "Missing artifact: " + str(path))
            entries[name] = path.read_bytes()
            artifacts[name] = sha(entries[name])
        for output in policy.get("evidence", []):
            entries[member(output["name"])] = Path(expand(output["path"])).read_bytes()
        require(snapshot() == source, "HEAD, tracked bytes or working tree changed during release")
        report = {"result": "PASS", "scope": "Windows x64 software release; not hardware cutover or installer certification",
                  "wire_version": WIRE, "source": source, "protocol_dependency": kit,
                  "identity": ["publisher_id", "source_id", "tracker_id"], "lifetime": ["publisher_id", "source_id"],
                  "historical_v1": "Separate compatibility/provenance only; not current protocol dependency",
                  "hardware_validation": "NOT RUN", "steamvr_interactive": "NOT RUN",
                  "android_deployment": "NOT RUN", "gui_installer": "NOT RUN",
                  "five_repository_wire_e2e": "NOT RUN",
                  "python": {"version": sys.version, "optimized_gate": sys.flags.optimize, "child_optimize": 0},
                  "platform": platform.platform(), "steps": steps, "tests": tests, "artifacts": artifacts,
                  "files_sha256": {n: sha(b) for n, b in entries.items()}}
        check_receipt(report, source, policy["required_steps"], [t["required"] for t in policy["test_groups"]])
        entries["evidence/release.json"] = encoded(report)
        directory = ROOT / "dist/release-v2" / source["commit"] / token
        directory.mkdir(parents=True)
        path = directory / (policy["repo"] + "-software-v2.zip")
        external = write_archive(path, entries, source["commit"])
        save(directory / "handoff.json", external)
        save(directory / "release.json", report)
        verify_release(directory, policy)
        require(snapshot() == source, "Source changed during packaging")
        status.update(result="PASS", source_commit=source["commit"], directory=str(directory),
                      artifact_sha256=external["sha256"])
        save(latest, status)
        print(json.dumps(status), flush=True)
    except BaseException as error:
        status.update(result="FAIL", error=str(error))
        save(latest, status)
        raise


if __name__ == "__main__":
    run(json.loads((ROOT / "scripts/release_v2.json").read_bytes()))
