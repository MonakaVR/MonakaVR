"""Run Task 5 software gates against a clean committed tree; publish actual evidence only.

Windows usage: python scripts/verify_task5.py --java-home <JDK17> --cmake <cmake.exe>
No hardware, sibling-repository writes, Git mutations or upstream regeneration.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import traceback
import xml.etree.ElementTree as ET
import zipfile


if __name__ == "__main__":
    raise SystemExit("Historical wire-v1 report is retired at current v2 HEAD. Use python scripts/release_v2.py; see docs/release-v2.md. Historical supplied artifacts remain immutable provenance.")

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "build/task5-validation"
DIST = ROOT / "dist"
PREPARED = "af6bcf69dfb992d330a25187f01f4fbab61c3498"
AUDITED = "eabf9c196c3859007139177bef3191ac83eaf97d"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def optimization_safety():
    files = (ROOT / "scripts/verify_task5.py", ROOT / "scripts/verify_task5_upstream.py")
    for path in files:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        require(not any(isinstance(node, ast.Assert) for node in ast.walk(tree)), f"Optimization-unsafe validation statement in {path}")
        require(
            not any(isinstance(node, ast.Name) and node.id == "__debug__" for node in ast.walk(tree)),
            f"Validation depends on __debug__ in {path}",
        )
    return {"result": "PASS", "files": [str(path.relative_to(ROOT)) for path in files], "statement_count": 0, "debug_dependency_count": 0}


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
            failures = int(suite.get("failures", "-1"))
            errors = int(suite.get("errors", "-1"))
            skipped = int(suite.get("skipped", "-1"))
            require(failures == 0, f"JUnit failures in {path}: {failures}")
            require(errors == 0, f"JUnit errors in {path}: {errors}")
            require(skipped == 0, f"JUnit skipped tests in {path}: {skipped}")
            require(".tracking.pico." not in suite.get("name", ""), f"Legacy PICO test remains active: {path}")
            target = EVIDENCE / "tests" / module / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(path.read_bytes())
            suites.append({"module": module, "name": suite.get("name"), "tests": int(suite.get("tests")),
                           "skipped": int(suite.get("skipped")), "sha256": sha(path.read_bytes()),
                           "cases": [case.get("name") for case in suite.findall("testcase")]})
    core_suites = [s for s in suites if s["name"] == "dev.monaka.tracking.MonakaRuntimeTests"]
    desktop_suites = [s for s in suites if s["name"] == "dev.monaka.tracking.desktop.MonakaDesktopTests"]
    require(len(core_suites) == 1, "Missing or duplicate MonakaRuntimeTests result")
    require(len(desktop_suites) == 1, "Missing or duplicate MonakaDesktopTests result")
    core = core_suites[0]
    desktop = desktop_suites[0]
    require(core["tests"] >= 17 and core["skipped"] == 0, "Required core regression count/status not met")
    require(desktop["tests"] >= 5 and desktop["skipped"] == 0, "Required desktop regression count/status not met")
    required = ["effectiveConstraintMovesComputedHipThroughExistingIkAndDoesNotRebuildPerFrame()",
                "bothRouteExcludesDirectSolverAndPrivateButRetainsRealHmd()",
                "pauseResumeDiscardsPoseHistoryAndRequiresNewSequence()",
                "pauseBacklogBeyondNormalDrainAdvancesReplayWatermarkBeforeResume()",
                "featureOffDoesNotOpenSocketRegisterHookOrChangeSlimeComputedOutput()",
                "separateProcessUdpEntersSamePipelineAndMovesExistingIk()"]
    missing = set(required) - set(core["cases"] + desktop["cases"])
    require(not missing, f"Required testcases missing: {sorted(missing)}")
    result = {"result": "PASS", "suites": suites,
              "counts": {m: sum(s["tests"] for s in suites if s["module"] == m) for m in ("core", "desktop")}}
    save(EVIDENCE / "test-summary.json", result)
    return result


def inspect_active_jar(jar, wire_major=1):
    with zipfile.ZipFile(jar) as z:
        bad_member = z.testzip()
        require(bad_member is None, f"Active JAR integrity failure: {bad_member}")
        entries = z.namelist()
        forbidden = (b"dev/monaka/tracking/pico/", b"dev.monaka.tracking.pico.", b"pico_ot_bridge_", b"PicoMotionTrackerBridgeNativeLibrary")
        active = [name for name in entries if name.endswith(".class") and name.startswith(("dev/monaka/", "dev/slimevr/"))]
        require(bool(active), "Active JAR contains no MonakaVR application classes")
        for name in active:
            data = z.read(name)
            require("/tracking/pico/" not in name, f"Legacy PICO class remains active: {name}")
            hits = [marker.decode() for marker in forbidden if marker in data]
            require(not hits, f"Forbidden active JAR symbols in {name}: {hits}")
        require(f"dev/monaka/protocol/v{wire_major}/MonakaCodec.class" in entries, "Selected fixed codec missing from active JAR")
        require("dev/monaka/tracking/desktop/MonakaServerIntegration.class" in entries, "Task5 desktop integration missing from active JAR")
        # JNA has legitimate unrelated desktop uses and is deliberately retained.
        require("com/sun/jna/Native.class" in entries, "Unrelated JNA runtime was unexpectedly removed")
    main = (ROOT / "server/desktop/src/main/java/dev/slimevr/desktop/Main.kt").read_text(encoding="utf-8")
    require("MonakaServerIntegration.startIfEnabled" in main, "Main does not compose Task5 integration")
    require("import dev.monaka.tracking.pico" not in main, "Main imports the legacy PICO integration")
    tick = (ROOT / "server/core/src/main/java/dev/slimevr/VRServer.kt").read_text(encoding="utf-8")
    run = tick[tick.index("override fun run()") :]
    markers = ["for (task in onTick)", "bridge.dataRead()", "tracker.tick(", "beforePoseUpdate?.run()", "humanPoseManager.update()"]
    positions = [run.find(marker) for marker in markers]
    require(all(position >= 0 for position in positions), f"Tick-order marker missing: {dict(zip(markers, positions))}")
    require(positions == sorted(positions) and len(set(positions)) == len(positions), f"VRServer tick ordering changed: {dict(zip(markers, positions))}")
    output = (ROOT / "server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufBridge.kt").read_text(encoding="utf-8")
    require(".setTrackerSerial(tracker.name)" in output, "Solver output serial no longer uses tracker identity")
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
            require(kotlin == cpp, f"C++/JVM error mismatch for {case['file']}: C++={cpp!r}, JVM={kotlin!r}")
            result = {"fixture": case["file"], "error": cpp.decode(), "result": "PASS"}
        else:
            require(not kotlin.startswith(b"ERROR:"), f"JVM rejected C++-accepted fixture {case['file']}: {kotlin!r}")
            require(json.loads(cpp) == json.loads(kotlin), f"C++/JVM semantic mismatch: {case['file']}")
            cpp_path = temp / f"{index}-cpp.json"; cpp_path.write_bytes(cpp)
            jvm_path = temp / f"{index}-jvm.json"; jvm_path.write_bytes(kotlin)
            require(json.loads(invoke([*jvm, cpp_path])) == json.loads(cpp), f"JVM failed C++ output: {case['file']}")
            require(json.loads(invoke([runner, jvm_path])) == json.loads(kotlin), f"C++ failed JVM output: {case['file']}")
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
        bad_member = z.testzip()
        require(bad_member is None, f"Task5 handoff ZIP integrity failure: {bad_member}")
        for entry in manifest["files"]:
            require(sha(z.read(entry["path"])) == entry["sha256"], f"Task5 handoff content mismatch: {entry['path']}")
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
        require(not git("status", "--porcelain"), "Commit current work before final validation")
        prepared_result = subprocess.run(["git", "merge-base", "--is-ancestor", PREPARED, head], cwd=ROOT, check=False)
        audited_result = subprocess.run(["git", "merge-base", "--is-ancestor", AUDITED, head], cwd=ROOT, check=False)
        require(prepared_result.returncode == 0, f"HEAD {head} is not a descendant of prepared base {PREPARED}")
        require(audited_result.returncode == 0, f"HEAD {head} is not a descendant of audited base {AUDITED}")
        env = dict(os.environ, JAVA_HOME=str(args.java_home))
        java = args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
        checks = status["checks"]
        checks["python_optimization_safety"] = optimization_safety()
        python_mode = ["-O"] if sys.flags.optimize else []
        checks["upstream"] = command("upstream", [sys.executable, *python_mode, "scripts/verify_task5_upstream.py"])
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        checks["build"] = command("gradle", [wrapper, ":server:core:test", ":server:desktop:test", ":server:desktop:shadowJar", "--no-daemon", "--console=plain", "--rerun-tasks"], env)
        checks["tests"] = test_evidence()
        jar = ROOT / "server/desktop/build/libs/slimevr.jar"
        checks["active_dependency"] = inspect_active_jar(jar)
        checks["desktop_help"] = command("desktop-help", [java, "-jar", jar, "--help"])
        require("monaka-mtp" in (EVIDENCE / "desktop-help.log").read_text(encoding="utf-8"), "Packaged desktop help lacks --monaka-mtp")
        checks["cpp_configure"] = command("cpp-configure", [args.cmake, "-S", "third_party/monaka-protocol/cpp", "-B", "build/task5-codec", "-DMONAKA_BUILD_TESTS=ON", "-DCMAKE_CXX_FLAGS=/EHsc"])
        checks["cpp_build"] = command("cpp-build", [args.cmake, "--build", "build/task5-codec", "--config", "Release"])
        ctest = args.cmake.with_name("ctest.exe" if os.name == "nt" else "ctest")
        checks["cpp_ctest"] = command("cpp-ctest", [ctest, "--test-dir", "build/task5-codec", "-C", "Release", "--output-on-failure"])
        runner = ROOT / "build/task5-codec/Release/monaka_codec_runner.exe"
        checks["codec_interop"] = interoperability(java, jar, runner)
        require(git("rev-parse", "HEAD") == head, f"HEAD changed during validation: expected {head}")
        require(not git("status", "--porcelain"), "Working tree changed during validation")
        status["status"] = "PASS"
        save(EVIDENCE / "validation-results.json", status)
        lock = json.loads((ROOT / "dependencies/task5-upstream.lock.json").read_bytes())
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
