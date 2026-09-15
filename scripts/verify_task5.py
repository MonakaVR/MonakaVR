"""Run Task 5 software gates against a clean committed tree; publish actual evidence only.

Windows usage: python scripts/verify_task5.py --java-home <JDK17> --cmake <cmake.exe>
No hardware, sibling-repository writes, Git mutations or upstream regeneration.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "build/task5-validation"
DIST = ROOT / "dist"
PREPARED = "af6bcf69dfb992d330a25187f01f4fbab61c3498"
AUDITED = "eabf9c196c3859007139177bef3191ac83eaf97d"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode().strip()


def command(name, args, env=None, timeout=900):
    log = EVIDENCE / (name + ".log")
    with log.open("wb") as output:
        result = subprocess.run([str(a) for a in args], cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"{name} exit {result.returncode}; see {log}")
    print(f"PASS {name}", flush=True)
    return {"result": "PASS", "command": [str(a) for a in args], "log": log.name, "sha256": sha(log.read_bytes())}


def test_evidence():
    suites = []
    for module in ("core", "desktop"):
        for path in sorted((ROOT / f"server/{module}/build/test-results/test").glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            assert int(suite.get("failures")) == 0 and int(suite.get("errors")) == 0, path
            assert ".tracking.pico." not in suite.get("name"), path
            target = EVIDENCE / "tests" / module / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
            suites.append({"module": module, "name": suite.get("name"), "tests": int(suite.get("tests")),
                           "skipped": int(suite.get("skipped")), "sha256": sha(path.read_bytes()),
                           "cases": [case.get("name") for case in suite.findall("testcase")]})
    core = next(s for s in suites if s["name"] == "dev.monaka.tracking.MonakaRuntimeTests")
    desktop = next(s for s in suites if s["name"] == "dev.monaka.tracking.desktop.MonakaDesktopTests")
    assert core["tests"] >= 16 and core["skipped"] == 0
    assert desktop["tests"] >= 5 and desktop["skipped"] == 0
    required = ["effectiveConstraintMovesComputedHipThroughExistingIkAndDoesNotRebuildPerFrame()",
                "bothRouteExcludesDirectSolverAndPrivateButRetainsRealHmd()",
                "pauseResumeDiscardsPoseHistoryAndRequiresNewSequence()",
                "featureOffDoesNotOpenSocketRegisterHookOrChangeSlimeComputedOutput()",
                "separateProcessUdpEntersSamePipelineAndMovesExistingIk()"]
    assert set(required) <= set(core["cases"] + desktop["cases"])
    result = {"result": "PASS", "suites": suites,
              "counts": {m: sum(s["tests"] for s in suites if s["module"] == m) for m in ("core", "desktop")}}
    save(EVIDENCE / "test-summary.json", result)
    return result


def inspect_active_jar(jar):
    with zipfile.ZipFile(jar) as z:
        assert z.testzip() is None
        entries = z.namelist()
        forbidden = (b"dev/monaka/tracking/pico/", b"dev.monaka.tracking.pico.", b"pico_ot_bridge_", b"PicoMotionTrackerBridgeNativeLibrary")
        active = [name for name in entries if name.endswith(".class") and name.startswith(("dev/monaka/", "dev/slimevr/"))]
        assert active
        for name in active:
            data = z.read(name)
            assert "/tracking/pico/" not in name, name
            assert not any(marker in data for marker in forbidden), name
        assert "dev/monaka/protocol/v1/MonakaCodec.class" in entries
        assert "dev/monaka/tracking/desktop/MonakaServerIntegration.class" in entries
        # JNA has legitimate unrelated desktop uses and is deliberately retained.
        assert "com/sun/jna/Native.class" in entries
    main = (ROOT / "server/desktop/src/main/java/dev/slimevr/desktop/Main.kt").read_text(encoding="utf-8")
    assert "MonakaServerIntegration.startIfEnabled" in main and "import dev.monaka.tracking.pico" not in main
    tick = (ROOT / "server/core/src/main/java/dev/slimevr/VRServer.kt").read_text(encoding="utf-8")
    run = tick[tick.index("override fun run()") :]
    assert run.index("for (task in onTick)") < run.index("bridge.dataRead()") < run.index("tracker.tick(") < run.index("beforePoseUpdate?.run()") < run.index("humanPoseManager.update()")
    output = (ROOT / "server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufBridge.kt").read_text(encoding="utf-8")
    assert ".setTrackerSerial(tracker.name)" in output
    result = {"result": "PASS", "jar_sha256": sha(jar.read_bytes()), "active_classes_scanned": len(active),
              "forbidden_markers": [x.decode() for x in forbidden], "legacy_classes_found": 0,
              "unrelated_jna_retained": True, "solver_serial_namespace": "human://", "hardware": "NOT RUN"}
    save(EVIDENCE / "active-dependency-check.json", result)
    return result


def interoperability(java, jar, runner):
    fixtures = ROOT / "third_party/monaka-protocol/fixtures"
    cases = json.loads((fixtures / "index.json").read_bytes())
    temp = EVIDENCE / "interop"
    temp.mkdir(exist_ok=True)
    results = []
    def invoke(args):
        return subprocess.check_output([str(a) for a in args], cwd=ROOT, timeout=30).strip()
    jvm = [java, "-cp", jar, "dev.monaka.tracking.desktop.FixedCodecProbe"]
    for index, case in enumerate(cases):
        path = fixtures / case["file"]
        cpp = invoke([runner, path])
        kotlin = invoke([*jvm, path])
        if cpp.startswith(b"ERROR:"):
            assert kotlin == cpp, (case["file"], cpp, kotlin)
            result = {"fixture": case["file"], "error": cpp.decode(), "result": "PASS"}
        else:
            assert not kotlin.startswith(b"ERROR:"), (case["file"], kotlin)
            assert json.loads(cpp) == json.loads(kotlin), case["file"]
            cpp_path = temp / f"{index}-cpp.json"; cpp_path.write_bytes(cpp)
            jvm_path = temp / f"{index}-jvm.json"; jvm_path.write_bytes(kotlin)
            assert json.loads(invoke([*jvm, cpp_path])) == json.loads(cpp)
            assert json.loads(invoke([runner, jvm_path])) == json.loads(kotlin)
            result = {"fixture": case["file"], "cpp_to_jvm": "PASS", "jvm_to_cpp": "PASS", "result": "PASS"}
        results.append(result)
    evidence = {"result": "PASS", "fixtures": len(results), "cross_language_directions": 2 * sum("cpp_to_jvm" in r for r in results),
                "runner_sha256": sha(runner.read_bytes()), "jar_sha256": sha(jar.read_bytes()), "cases": results}
    save(EVIDENCE / "codec-interop.json", evidence)
    print(f"PASS fixed codec interoperability: {evidence['fixtures']} fixtures", flush=True)
    return evidence


def responsibility(path):
    if path.startswith("third_party/"): return "Exact supplied Task 1 kit, codec and notices; no local wire changes"
    if path.startswith("dependencies/"): return "Hash-verified actual upstream handoff/provenance"
    if path.startswith("docs/"): return "Migration, baseline failures, prerequisite gates and handoff evidence"
    if path.startswith("scripts/"): return "Reproducible validation and packaging with actual hashes"
    if "DiagnosticsTests" in path: return "Keep calculations and publish diagnostic trace through JUnit instead of unconditional failure"
    if "/test/" in path: return "Behavioral regression for runtime, component fallback, existing IK and desktop lifecycle"
    if "build.gradle" in path: return "Fixed codec dependency, upstream verification and legacy reference-only source sets"
    if "/mtp/" in path: return "Fixed-codec intake, immutable sample admission and incremental source lifecycle"
    if "HumanSkeleton" in path or "SkeletonInputView" in path or "ConstraintIkWriteback" in path: return "Minimal capability-correct inputs to existing FK/IK, with no solver rewrite"
    if "VRServer" in path or path.endswith("/Main.kt"): return "Gated composition and correctly ordered pre-pose update hook"
    if "/desktop/" in path: return "Loopback worker, feature gate, lifecycle or standalone codec verification"
    if path.endswith((".gitignore", ".gitattributes")): return "Preserve exact artifact bytes and keep generated handoff outside source history"
    return "Vendor-neutral runtime, assignments, profile semantics or feedback exclusion"


def package(head, report, jar):
    source_files = git("ls-files", "--recurse-submodules").splitlines()
    entries = {}
    for name in source_files:
        path = ROOT / name
        if path.is_file(): entries["source/" + name] = path.read_bytes()
    entries["runtime/slimevr.jar"] = jar.read_bytes()
    for path in EVIDENCE.rglob("*"):
        if path.is_file(): entries["evidence/" + path.relative_to(EVIDENCE).as_posix()] = path.read_bytes()
    manifest = {"source_commit": head, "files": [{"path": name, "sha256": sha(data), "bytes": len(data)} for name, data in sorted(entries.items())]}
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    archive = DIST / "monakavr-task5-handoff.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(entries.items()): z.writestr(name, data)
        z.writestr("handoff-content-manifest.json", manifest_bytes)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for entry in manifest["files"]: assert sha(z.read(entry["path"])) == entry["sha256"]
    artifact = {"filename": archive.name, "sha256": sha(archive.read_bytes()), "source_commit": head}
    external = {"source_commit": head, "artifact": artifact, "content_manifest_sha256": sha(manifest_bytes), "hardware": "NOT RUN"}
    external_path = DIST / "monakavr-task5-handoff.handoff.json"
    save(external_path, external)
    report["handoff_artifacts"] = [artifact, {"filename": external_path.name, "sha256": sha(external_path.read_bytes()), "source_commit": head},
                                   {"filename": "runtime/slimevr.jar", "sha256": sha(jar.read_bytes()), "source_commit": head}]
    save(DIST / "task5-final-report.json", report)
    print(f"PASS handoff {artifact['filename']} SHA256={artifact['sha256']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True); DIST.mkdir(exist_ok=True)
    head = git("rev-parse", "HEAD")
    status = {"source_commit": head, "status": "RUNNING", "checks": {}, "hardware": "NOT RUN"}
    # Retire a prior generated PASS immediately. New evidence is never inferred from an old run.
    save(EVIDENCE / "validation-results.json", status)
    save(DIST / "task5-final-report.json", {"HEAD_SHA": head, "phase_or_DoD_status": "RUNNING", "hardware_validation": "NOT RUN"})
    try:
        assert not git("status", "--porcelain"), "Commit current work before final validation"
        subprocess.check_call(["git", "merge-base", "--is-ancestor", PREPARED, head], cwd=ROOT)
        subprocess.check_call(["git", "merge-base", "--is-ancestor", AUDITED, head], cwd=ROOT)
        env = dict(os.environ, JAVA_HOME=str(args.java_home))
        java = args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
        checks = status["checks"]
        checks["upstream"] = command("upstream", [sys.executable, "scripts/verify_task5_upstream.py"])
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        checks["build"] = command("gradle", [wrapper, ":server:core:test", ":server:desktop:test", ":server:desktop:shadowJar", "--no-daemon", "--console=plain", "--rerun-tasks"], env)
        checks["tests"] = test_evidence()
        jar = ROOT / "server/desktop/build/libs/slimevr.jar"
        checks["active_dependency"] = inspect_active_jar(jar)
        checks["desktop_help"] = command("desktop-help", [java, "-jar", jar, "--help"])
        assert "monaka-mtp" in (EVIDENCE / "desktop-help.log").read_text(encoding="utf-8")
        checks["cpp_configure"] = command("cpp-configure", [args.cmake, "-S", "third_party/monaka-protocol/cpp", "-B", "build/task5-codec", "-DMONAKA_BUILD_TESTS=ON", "-DCMAKE_CXX_FLAGS=/EHsc"])
        checks["cpp_build"] = command("cpp-build", [args.cmake, "--build", "build/task5-codec", "--config", "Release"])
        ctest = args.cmake.with_name("ctest.exe" if os.name == "nt" else "ctest")
        checks["cpp_ctest"] = command("cpp-ctest", [ctest, "--test-dir", "build/task5-codec", "-C", "Release", "--output-on-failure"])
        runner = ROOT / "build/task5-codec/Release/monaka_codec_runner.exe"
        checks["codec_interop"] = interoperability(java, jar, runner)
        assert git("rev-parse", "HEAD") == head and not git("status", "--porcelain")
        status["status"] = "PASS"
        save(EVIDENCE / "validation-results.json", status)
        lock = json.loads((ROOT / "dependencies/task5-upstream.lock.json").read_bytes())
        protocol = json.loads((ROOT / "dependencies/monaka-protocol.lock.json").read_bytes())
        changed = git("diff", "--name-only", PREPARED, head).splitlines()
        report = {
            "repository": "MonakaVR/MonakaVR", "audited_base_branch": "feature/pico-motion-tracker-bridge-backend", "audited_base_sha": AUDITED,
            "actual_base_branch": "refactor/monaka-layer-separation", "actual_base_sha": PREPARED,
            "base_change_reason": "Started from prepared Task 5 descendant after verifying audited ancestry; desktop branch, managed-work exception not needed",
            "work_branch": git("branch", "--show-current"), "HEAD_SHA": head,
            "protocol_version": "C1 wire 1.0 candidate / master reconciliation pending", "schema_commit": "04f2d6c831c68a65edfbfb66ff8838d1c9d78535",
            "protocol_kit_sha256": lock["task1_kit_sha256"], "contract_c1_sha256": lock["c1_sha256"],
            "task4_source_commit": lock["task4_source_commit"], "task4_handoff_sha256": lock["task4_handoff_sha256"],
            "changed_files": [{"path": p, "reason": responsibility(p)} for p in changed],
            "build_result": checks["build"], "unit_test_result": checks["tests"]["counts"],
            "mock_or_cross_language_result": {"separate_process_udp": "PASS", "fixed_codec": checks["codec_interop"]},
            "ik_writeback_result": "PASS in-memory and separate-JVM UDP through EffectiveConstraint, existing IK and computed hip numerical change",
            "feedback_exclusion_result": "PASS both-route Direct/solver/private input exclusion; real HMD retained",
            "active_dependency_evidence": checks["active_dependency"],
            "hardware_validation": {key: "NOT RUN" for key in ["PICO", "five_tracker", "VIVE_dongle_Hub_stopped", "axes_scale_quaternion_map", "coexistence", "HMD", "SteamVR", "physical_Direct_vs_MTP", "hardware_cutover"]},
            "compatibility_status": "Software regression PASS; baseline failures retained; hardware gates pending",
            "phase_or_DoD_status": "Task 5 software DoD complete / hardware NOT RUN",
            "unresolved_issues": ["C1 master reconciliation pending", "Physical tracking and cutover require independent hardware evidence", "Legacy native smoke tests originally failed due to missing configured artifacts; archived as FAIL, not relabeled", "Existing Kotlin/Gradle/deprecation and Android signing warnings remain"],
            "followup_required_in_task2": "This JAR has no active PICO receiver C ABI/carrier. Task 2 Phase B still requires its planned compatibility and hardware cutover gates; no automatic legacy removal is authorized by software evidence alone.",
            "validation_evidence_sha256": sha((EVIDENCE / "validation-results.json").read_bytes()),
            "submodules": git("submodule", "status", "--recursive"),
        }
        package(head, report, jar)
    except BaseException as error:
        status["status"] = "FAIL"; status["error"] = repr(error)
        save(EVIDENCE / "validation-results.json", status)
        save(DIST / "task5-final-report.json", {"HEAD_SHA": head, "phase_or_DoD_status": "FAIL", "error": repr(error), "hardware_validation": "NOT RUN"})
        traceback.print_exc()
        raise


if __name__ == "__main__":
    main()
