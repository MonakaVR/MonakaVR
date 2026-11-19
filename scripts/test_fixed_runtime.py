"""Focused fixed-runtime swap and launcher checks using temporary files only."""

from contextlib import redirect_stdout, redirect_stderr
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_runtime
from workspace_runtime import stable_launcher


class RuntimePromotionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name).resolve()
        self.runtime = self.repo / "Build/runtime"
        self.stage = self.runtime / "package-stage/win-unpacked"
        self.target = self.runtime / "win-unpacked"
        self.manifest = self.repo / "Build/provenance/windows.json"
        for name in (
            "SlimeVR.exe",
            "slimevr.jar",
            "SlimeVR-Bindings-Provider.exe",
            "openvr_api.dll",
            "resources/app.asar",
            "resources/app.asar.unpacked/out/main/chunks/helper.jar",
        ):
            file = self.stage / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b"candidate")
        self.manifest.parent.mkdir()
        self.enterContext(patch.object(build_runtime, "ROOT", self.repo))

    def exercise_failed_completion(self, prior_runtime, prior_manifest):
        if prior_runtime:
            self.target.mkdir()
            (self.target / "old.exe").write_bytes(b"old runtime")
        if prior_manifest:
            self.manifest.write_bytes(b"old manifest")

        def fail_after_manifest_write():
            self.assertEqual((self.target / "SlimeVR.exe").read_bytes(), b"candidate")
            self.manifest.write_bytes(b"candidate manifest")
            raise build_runtime.ContractError("injected completion failure")

        with self.assertRaisesRegex(build_runtime.ContractError, "injected completion"):
            build_runtime.promote_distribution(self.runtime, fail_after_manifest_write)
        self.assertEqual((self.stage / "SlimeVR.exe").read_bytes(), b"candidate")
        if prior_runtime:
            self.assertEqual((self.target / "old.exe").read_bytes(), b"old runtime")
        else:
            self.assertFalse(self.target.exists())
        if prior_manifest:
            self.assertEqual(self.manifest.read_bytes(), b"old manifest")
        else:
            self.assertFalse(self.manifest.exists())

    def test_failed_completion_restores_existing_runtime_and_manifest(self):
        self.exercise_failed_completion(prior_runtime=True, prior_manifest=True)

    def test_failed_completion_restores_runtime_without_creating_manifest(self):
        self.exercise_failed_completion(prior_runtime=True, prior_manifest=False)

    def test_failed_completion_preserves_manifest_when_runtime_is_absent(self):
        self.exercise_failed_completion(prior_runtime=False, prior_manifest=True)

    def test_failed_first_build_leaves_no_selected_runtime_or_manifest(self):
        self.exercise_failed_completion(prior_runtime=False, prior_manifest=False)

    def test_success_promotes_runtime_and_keeps_backup(self):
        self.target.mkdir()
        (self.target / "old.exe").write_bytes(b"old runtime")
        self.manifest.write_bytes(b"old manifest")

        build_runtime.promote_distribution(
            self.runtime, lambda: self.manifest.write_bytes(b"new manifest")
        )

        self.assertFalse(self.stage.exists())
        self.assertEqual((self.target / "SlimeVR.exe").read_bytes(), b"candidate")
        self.assertEqual(self.manifest.read_bytes(), b"new manifest")
        backups = list((self.runtime / "previous-success").glob("*/old.exe"))
        self.assertEqual([file.read_bytes() for file in backups], [b"old runtime"])


class ObservationLauncherTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="fixed runtime ")
        self.addCleanup(temporary.cleanup)
        self.workspace = Path(temporary.name).resolve()
        self.product = "PicoMotionTrackerBridge"
        self.repo = self.workspace / self.product
        self.runtime = self.repo / "Build/runtime"
        self.gui = self.runtime / "control/Release/pico_ot_steamvr_control.exe"
        self.service = self.runtime / "Release/pico_ot_bridge_service.exe"
        for binary in (self.gui, self.service):
            binary.parent.mkdir(parents=True, exist_ok=True)
            binary.touch()
        local = self.workspace / "isolated-user-data"
        self.route = local / self.product / "output_route.txt"
        self.route.parent.mkdir(parents=True)
        self.enterContext(patch.dict(stable_launcher.os.environ, {"LOCALAPPDATA": str(local)}))

    def test_check_ignores_absent_malformed_and_stale_route(self):
        for route in (None, b"\xff\xfe\x00", b"steamvr-direct\n"):
            with self.subTest(route=route):
                if route is not None:
                    self.route.write_bytes(route)
                stdout = StringIO()
                with redirect_stdout(stdout), patch.object(
                    stable_launcher.subprocess, "Popen", side_effect=AssertionError("check launched")
                ):
                    self.assertEqual(
                        stable_launcher.main(self.product, self.workspace, ["--check"]), 0
                    )
                result = json.loads(stdout.getvalue())
                self.assertEqual(result, {
                    "status": "READY",
                    "runtime": str(self.runtime),
                    "cwd": str(self.repo),
                    "process_started": False,
                    "argv": [str(self.gui), str(self.repo), "--service-exe", str(self.service)],
                })
                if route is not None:
                    self.assertEqual(self.route.read_bytes(), route)

    def test_fixed_payload_is_required_even_when_a_legacy_copy_exists(self):
        for missing in (self.gui, self.service):
            with self.subTest(missing=missing.name), redirect_stderr(StringIO()) as stderr:
                decoy = self.repo / missing.relative_to(self.runtime)
                decoy.parent.mkdir(parents=True, exist_ok=True)
                missing.rename(decoy)
                try:
                    self.assertEqual(
                        stable_launcher.main(self.product, self.workspace, ["--check"]), 2
                    )
                    self.assertIn("Missing fixed Build/runtime launch payload", stderr.getvalue())
                    self.assertIn(missing.name, stderr.getvalue())
                finally:
                    decoy.rename(missing)


if __name__ == "__main__":
    unittest.main()
