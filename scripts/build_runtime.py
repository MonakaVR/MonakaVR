"""Explicit local Windows desktop build. Never run, install or register the result."""

import argparse
import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "Build" / "runtime"


def executable(name, override):
    explicit = os.environ.get(override)
    if explicit:
        return str(Path(explicit).resolve()) if Path(explicit).is_file() else None
    return shutil.which(name)


def cmake_executable():
    found = executable("cmake.exe", "MONAKA_CMAKE")
    if found or os.environ.get("MONAKA_CMAKE"):
        return found
    for key in ("ProgramFiles", "ProgramFiles(x86)"):
        folder = os.environ.get(key)
        if folder:
            pattern = str(Path(folder) / "Microsoft Visual Studio" / "*" / "*" / "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe")
            matches = sorted(glob.glob(pattern), reverse=True)
            if matches:
                return matches[0]
    return None


def run(command, cwd, env, log):
    print(subprocess.list2cmdline(command), flush=True)
    if Path(command[0]).suffix.lower() in (".cmd", ".bat"):
        command = '"' + os.environ["COMSPEC"] + '" /d /s /c "' + subprocess.list2cmdline(command) + '"'
    with log.open("w", encoding="utf-8") as output:
        try:
            return subprocess.run(command, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT, check=False).returncode
        except OSError as error:
            output.write(str(error))
            return 2


def promote_distribution(output):
    output = output.resolve()
    repository = ROOT.resolve()
    if not output.is_relative_to(repository) or output == repository:
        raise ValueError("Runtime output is outside the repository")
    stage = (output / "package-stage/win-unpacked").resolve()
    target = (output / "win-unpacked").resolve()
    backup = (output / "previous-success" / uuid.uuid4().hex).resolve()
    for path in (stage, target, backup):
        if not path.is_relative_to(output) or path == output:
            raise ValueError("Runtime promotion path escapes the repository output")
    required = ["SlimeVR.exe", "slimevr.jar", "SlimeVR-Bindings-Provider.exe",
                "openvr_api.dll", "resources/app.asar"]
    absent = [str(stage / name) for name in required if not (stage / name).is_file()]
    if absent:
        raise ValueError("Packaged distribution is incomplete: " + ", ".join(absent))
    moved = False
    if target.exists():
        backup.parent.mkdir(parents=True, exist_ok=True)
        target.rename(backup)
        moved = True
    try:
        stage.rename(target)
    except OSError:
        if moved and not target.exists():
            backup.rename(target)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="Use existing pinned sources/dependencies only; never download")
    parser.add_argument("--plan", action="store_true", help="Print commands without running them")
    args = parser.parse_args()
    cmake = cmake_executable()
    pnpm = executable("pnpm.cmd", "MONAKA_PNPM")
    java = executable("java.exe", "MONAKA_JAVA")
    if not java and os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
        if candidate.is_file():
            java = str(candidate)
    missing = [name for name, value in (("CMake", cmake), ("pnpm", pnpm), ("Java JDK 17+", java)) if value is None]
    env = os.environ.copy()
    env.setdefault("GRADLE_USER_HOME", str(OUTPUT.parent / "Dependencies/gradle"))
    env["HUSKY"] = "0"
    env["CI"] = "true"
    env["MONAKA_LOCAL_RUNTIME_BUILD"] = "1"
    if java:
        env["JAVA_HOME"] = str(Path(java).resolve().parent.parent)
    # These are the dependency lifecycle packages permitted by the tracked
    # pnpm-workspace.yaml. The root prepare hook is not run (no hook install).
    rebuild = ["@parcel/watcher", "@sentry/cli", "@swc/core", "electron",
               "electron-winstaller", "esbuild", "register-scheme", "unrs-resolver"]
    commands = []
    if not args.offline:
        commands.append(("submodules", ["git", "submodule", "update", "--init", "--recursive"], ROOT))
    install = [pnpm or "pnpm.cmd", "install", "--frozen-lockfile", "--ignore-scripts",
               "--store-dir", str(OUTPUT.parent / "Dependencies/pnpm")]
    if args.offline:
        install.append("--offline")
    commands.append(("dependencies", install, ROOT))
    if not args.offline:
        commands.append(("dependency-scripts", [pnpm or "pnpm.cmd", "--recursive", "rebuild", *rebuild], ROOT))
    commands += [
        ("desktop", [str(ROOT / "gradlew.bat"), ":server:desktop:shadowJar", ":server:desktop:dependencies", "--configuration", "runtimeClasspath", "--no-daemon", "--console=plain", "--max-workers=2"] + (["--offline"] if args.offline else []), ROOT),
        ("bindings-configure", [cmake or "cmake.exe", "-S", str(ROOT / "bindings-provider"), "-B", str(OUTPUT / "bindings-provider"), "-A", "x64"], ROOT),
        ("bindings-build", [cmake or "cmake.exe", "--build", str(OUTPUT / "bindings-provider"), "--config", "Release"], ROOT),
        ("protocol", [pnpm or "pnpm.cmd", "run", "update-solarxr"], ROOT),
        ("gui", [pnpm or "pnpm.cmd", "--dir", "gui", "build"], ROOT),
        ("package", [pnpm or "pnpm.cmd", "--dir", "gui", "exec", "electron-builder", "--dir", "--win", "--x64", "--config", str(ROOT / "scripts/electron-builder.runtime.cjs"), "--publish", "never"], ROOT),
    ]
    if args.plan:
        print(json.dumps({"missing_tools": missing, "commands": [{"name": name, "argv": argv, "cwd": str(cwd)} for name, argv, cwd in commands], "runtime": str(OUTPUT / "win-unpacked")}, indent=2))
        return 0
    if missing:
        print("Missing build tools: " + ", ".join(missing), file=sys.stderr)
        return 2
    if args.offline:
        required = [ROOT / "solarxr-protocol/protocol/java/build.gradle.kts",
                    ROOT / "solarxr-protocol/lib/flatbuffers/include/flatbuffers/flatbuffers.h",
                    ROOT / "bindings-provider/openvr/headers/openvr.h",
                    ROOT / "gui/node_modules/electron/dist/electron.exe"]
        absent = [str(path) for path in required if not path.is_file()]
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
        version = re.search(r"gradle-([0-9.]+)-bin\.zip", wrapper).group(1)
        cached = Path(env["GRADLE_USER_HOME"]) / "wrapper/dists" / ("gradle-" + version + "-bin")
        if not any(cached.glob("*/gradle-" + version + "/bin/gradle.bat")):
            # --offline is a Gradle option, not a wrapper-download option.
            absent.append(str(cached / ("*/gradle-" + version + "/bin/gradle.bat")))
        if absent:
            print("Offline build needs already prepared pinned dependencies:\n" + "\n".join(absent), file=sys.stderr)
            return 2
    logs = OUTPUT / "build-logs" / uuid.uuid4().hex
    logs.mkdir(parents=True, exist_ok=True)
    for name, command, cwd in commands:
        code = run(command, cwd, env, logs / (name + ".log"))
        if code:
            print("Build failed at " + name + "; see " + str(logs / (name + ".log")), file=sys.stderr)
            return code
    try:
        promote_distribution(OUTPUT)
    except (OSError, ValueError) as error:
        print("Runtime promotion failed: " + str(error), file=sys.stderr)
        return 2
    print("Built " + str(OUTPUT / "win-unpacked") + ". No application was started.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
