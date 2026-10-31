"""File-only launcher checks; no GUI, server, native runtime or device is started."""

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr, redirect_stdout
import io
import subprocess


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("runtime_launcher", ROOT / "scripts/runtime_launcher.py")
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)
BUILD_SPEC = importlib.util.spec_from_file_location("build_runtime", ROOT / "scripts/build_runtime.py")
BUILDER = importlib.util.module_from_spec(BUILD_SPEC)
BUILD_SPEC.loader.exec_module(BUILDER)


class PortableRuntimeTests(unittest.TestCase):
    def setUp(self):
        temporary_root = ROOT / "Build/test-temporary"
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="runtime with spaces ", dir=temporary_root)
        self.root = Path(self.temporary.name)
        self.runtime = self.root / "Build/runtime/win-unpacked"
        self.java = self.root / "jdk/bin/java.exe"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"fixture-not-executable")
        self.environment = patch.dict(os.environ, {"MONAKA_JAVA": str(self.java)})
        self.environment.start()
        self.runtime_patch = patch.object(LAUNCHER, "RUNTIME", self.runtime)
        self.runtime_patch.start()

    def tearDown(self):
        self.runtime_patch.stop()
        self.environment.stop()
        self.temporary.cleanup()

    def populate(self):
        # Minimal real locked Electron 40 distribution plus the product files.
        # In particular the official archive does not ship chrome_elf.dll.
        for item in (
            "slimevr.jar", "SlimeVR-Bindings-Provider.exe", "openvr_api.dll",
            "SlimeVR.exe", "resources/app.asar", "ffmpeg.dll", "icudtl.dat",
            "resources.pak", "v8_context_snapshot.bin", "locales/en-US.pak",
            "resources/app.asar.unpacked/out/main/chunks/JavaVersion-fixture.jar",
        ):
            path = self.runtime / item
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")

    def test_missing_files_are_reported_without_running_or_writing(self):
        before = sorted(str(path) for path in self.root.rglob("*"))
        with patch.object(LAUNCHER.subprocess, "run", side_effect=AssertionError("process denied")):
            result = LAUNCHER.preflight("gui")
        self.assertEqual(result["status"], "MISSING")
        self.assertIn(str(self.runtime / "SlimeVR.exe"), result["missing"])
        self.assertFalse(result["process_started"])
        self.assertEqual(before, sorted(str(path) for path in self.root.rglob("*")))

    def test_file_presence_is_distinct_from_runtime_or_configuration_readiness(self):
        self.populate()
        with patch.object(LAUNCHER.subprocess, "run", side_effect=AssertionError("process denied")):
            result = LAUNCHER.preflight("gui")
        self.assertEqual(result["missing"], [])
        self.assertFalse((self.runtime / "chrome_elf.dll").exists())
        self.assertEqual(result["argv"], [str(self.runtime / "SlimeVR.exe"), "--path", str(self.runtime)])
        for key in ("process_started", "configuration_validated", "runtime_readiness_verified", "java_version_verified"):
            self.assertFalse(result[key])

    def test_bad_explicit_java_does_not_fall_back_to_path(self):
        with patch.dict(os.environ, {"MONAKA_JAVA": str(self.root / "missing.exe")}):
            with patch.object(LAUNCHER.shutil, "which", side_effect=AssertionError("fallback denied")):
                self.assertIsNone(LAUNCHER.java_executable())

    def test_server_uses_the_same_fixed_distribution_as_gui(self):
        self.populate()
        result = LAUNCHER.preflight("server")
        self.assertEqual(result["missing"], [])
        self.assertEqual(result["argv"], [str(self.java.resolve()), "-jar", str(self.runtime / "slimevr.jar"), "run"])
        self.assertEqual(result["cwd"], str(self.runtime))

    def test_offline_missing_cache_stops_before_any_external_command(self):
        wrapper = self.root / "gradle/wrapper/gradle-wrapper.properties"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text("distributionUrl=https://services.gradle.org/distributions/gradle-8.14.4-bin.zip")
        with patch.object(BUILDER, "ROOT", self.root), patch.object(BUILDER, "OUTPUT", self.root / "Build/runtime"):
            with patch.object(BUILDER, "executable", return_value=str(self.java)), patch.object(BUILDER, "cmake_executable", return_value=str(self.java)):
                with patch.object(BUILDER, "run", side_effect=AssertionError("external command denied")):
                    with patch.object(BUILDER.sys, "argv", ["build_runtime.py", "--offline"]), redirect_stderr(io.StringIO()):
                        self.assertEqual(BUILDER.main(), 2)

    def test_offline_plan_preserves_prepared_dependencies_without_install(self):
        output = io.StringIO()
        with patch.object(BUILDER.sys, "argv", ["build_runtime.py", "--offline", "--plan"]), redirect_stdout(output):
            self.assertEqual(BUILDER.main(), 0)
        import json
        commands = json.loads(output.getvalue())["commands"]
        self.assertNotIn("submodules", [item["name"] for item in commands])
        self.assertNotIn("dependencies", [item["name"] for item in commands])
        self.assertNotIn("dependency-scripts", [item["name"] for item in commands])
        desktop = next(item["argv"] for item in commands if item["name"] == "desktop")
        self.assertIn("--offline", desktop)
        self.assertFalse(any(token in ("install", "submodule", "rebuild") for item in commands for token in item["argv"]))

    def test_online_plan_retains_pinned_dependency_preparation(self):
        output = io.StringIO()
        with patch.object(BUILDER.sys, "argv", ["build_runtime.py", "--plan"]), redirect_stdout(output):
            self.assertEqual(BUILDER.main(), 0)
        import json
        commands = json.loads(output.getvalue())["commands"]
        self.assertEqual([item["name"] for item in commands[:3]], ["submodules", "dependencies", "dependency-scripts"])
        self.assertEqual(commands[0]["argv"], ["git", "submodule", "update", "--init", "--recursive"])
        self.assertIn("--frozen-lockfile", commands[1]["argv"])
        self.assertIn("--ignore-scripts", commands[1]["argv"])
        self.assertNotIn("--offline", commands[1]["argv"])
        self.assertEqual(commands[2]["argv"][1:3], ["--recursive", "rebuild"])

    def test_incomplete_package_keeps_previous_runtime(self):
        output = self.root / "Build/runtime"
        previous = output / "win-unpacked/previous.exe"
        previous.parent.mkdir(parents=True)
        previous.write_bytes(b"old-working-binary")
        with self.assertRaises(ValueError):
            BUILDER.promote_distribution(output)
        self.assertEqual(previous.read_bytes(), b"old-working-binary")

    def test_complete_package_preserves_previous_runtime_before_promotion(self):
        output = self.root / "Build/runtime"
        previous = output / "win-unpacked/previous.exe"
        previous.parent.mkdir(parents=True)
        previous.write_bytes(b"old-working-binary")
        stage = output / "package-stage/win-unpacked"
        for name in ("SlimeVR.exe", "slimevr.jar", "SlimeVR-Bindings-Provider.exe", "openvr_api.dll", "resources/app.asar", "resources/app.asar.unpacked/out/main/chunks/JavaVersion-fixture.jar"):
            path = stage / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"new-build")
        BUILDER.promote_distribution(output)
        self.assertEqual((output / "win-unpacked/SlimeVR.exe").read_bytes(), b"new-build")
        backups = list((output / "previous-success").glob("*/previous.exe"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), b"old-working-binary")

    def test_package_without_unpacked_helper_cannot_replace_previous_runtime(self):
        output = self.root / "Build/runtime"
        previous = output / "win-unpacked/previous.exe"
        previous.parent.mkdir(parents=True)
        previous.write_bytes(b"old-working-binary")
        stage = output / "package-stage/win-unpacked"
        for name in ("SlimeVR.exe", "slimevr.jar", "SlimeVR-Bindings-Provider.exe", "openvr_api.dll", "resources/app.asar"):
            path = stage / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"new-build-with-packed-helper-only")
        with self.assertRaisesRegex(ValueError, "app.asar.unpacked"):
            BUILDER.promote_distribution(output)
        self.assertEqual(previous.read_bytes(), b"old-working-binary")

    def test_offline_precheck_accepts_exact_pinned_gradle_filename(self):
        cache = self.root / "Build/Dependencies/gradle"
        for name in (
            "solarxr-protocol/protocol/java/build.gradle",
            "solarxr-protocol/lib/flatbuffers/include/flatbuffers/flatbuffers.h",
            "bindings-provider/openvr/headers/openvr.h",
            "gui/node_modules/electron/dist/electron.exe",
            "Build/Dependencies/gradle/wrapper/dists/gradle-8.14.4-bin/fixture/gradle-8.14.4/bin/gradle.bat",
        ):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        wrapper = self.root / "gradle/wrapper/gradle-wrapper.properties"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_text("distributionUrl=https://services.gradle.org/distributions/gradle-8.14.4-bin.zip")
        with patch.object(BUILDER, "ROOT", self.root), patch.object(BUILDER, "OUTPUT", self.root / "Build/runtime"):
            with patch.dict(os.environ, {"GRADLE_USER_HOME": str(cache)}), patch.object(BUILDER, "run", return_value=0) as commands:
                with patch.object(BUILDER, "promote_distribution"), patch.object(BUILDER, "executable", return_value=str(self.java)), patch.object(BUILDER, "cmake_executable", return_value=str(self.java)):
                    with patch.object(BUILDER.sys, "argv", ["build_runtime.py", "--offline"]), redirect_stdout(io.StringIO()):
                        self.assertEqual(BUILDER.main(), 0)
        self.assertTrue(commands.called)

    def test_output_outside_repository_is_rejected_before_file_or_rename_work(self):
        repository = self.root / "consumer-repository"
        repository.mkdir()
        external = self.root / "external-output"
        external.mkdir()
        sentinel = external / "working.exe"
        sentinel.write_bytes(b"preserve")
        with patch.object(BUILDER, "ROOT", repository):
            with self.assertRaisesRegex(ValueError, "outside the repository"):
                BUILDER.promote_distribution(external)
        self.assertEqual(sentinel.read_bytes(), b"preserve")
        self.assertEqual(list(external.iterdir()), [sentinel])

    @unittest.skipUnless(os.name == "nt", "Windows junction regression")
    def test_output_junction_to_outside_repository_is_rejected(self):
        repository = self.root / "junction-consumer"
        junction = repository / "Build/runtime"
        junction.parent.mkdir(parents=True)
        external = self.root / "junction-external-output"
        external.mkdir()
        sentinel = external / "working.exe"
        sentinel.write_bytes(b"preserve")
        # Both test paths are within this repository's Build/test-temporary.
        # The simulated repository boundary is intentionally narrower.
        for path in (junction, external):
            self.assertTrue(path.resolve().is_relative_to(ROOT.resolve() / "Build"))
        command = f'"{os.environ["COMSPEC"]}" /d /s /c "mklink /J "{junction}" "{external}""'
        result = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        with patch.object(BUILDER, "ROOT", repository):
            with self.assertRaisesRegex(ValueError, "outside the repository"):
                BUILDER.promote_distribution(junction)
        self.assertEqual(sentinel.read_bytes(), b"preserve")
        self.assertEqual(list(external.iterdir()), [sentinel])


if __name__ == "__main__":
    unittest.main()
