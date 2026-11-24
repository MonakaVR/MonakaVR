"""Focused filesystem tests; never start a product, contact hardware, or use a real runtime."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import build_provenance as build
import runtime_contract as runtime
import stable_launcher as launcher


class PromotionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="monaka-promotion-test-")
        self.workspace = Path(self.temporary.name).resolve()
        self.repo = self.workspace / "MonakaVR"
        self.repo.mkdir()
        self.baseline = self.workspace / 'rollback-baseline/manifest.json'
        build.write_json(self.baseline, {'fixture': 'preserved previous runtime'})
        self.source = {"repository": "MonakaVR", "branch": "fix/selected", "commit": "a" * 40,
                       "tree": "b" * 40, "dirty": False, "patch_sha256": None}
        self.mock_build = patch.object(build, "source_info", side_effect=lambda _: copy.deepcopy(self.source))
        self.mock_runtime = patch.object(runtime, "source_info", side_effect=lambda _: copy.deepcopy(self.source))
        self.mock_build.start()
        self.mock_runtime.start()
        self.addCleanup(self.mock_build.stop)
        self.addCleanup(self.mock_runtime.stop)
        self.addCleanup(self.temporary.cleanup)
        self.mock_environment = patch.dict(launcher.os.environ, {'LOCALAPPDATA': str(self.workspace / 'localappdata')})
        self.mock_environment.start()
        self.addCleanup(self.mock_environment.stop)
        runtime.select_source(self.repo, ["windows"])

    def candidate(self, data=b"selected application"):
        artifact = self.repo / "Build" / "runtime" / "app.exe"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_bytes(data)
        start = build.begin_build(self.repo)
        result = build.finish_build(self.repo, start, [("Build/runtime/app.exe", "app.exe")])
        # Historical explicit-promotion fixtures adopt a candidate themselves;
        # normal builds do not publish or depend on this optional catalog.
        catalog = build.load_catalog(self.workspace)
        catalog['products']['MonakaVR']['latest_candidates']['windows'] = result['build_candidate_id']
        build.write_json(build.catalog_path(self.workspace), catalog)
        return result

    def accept(self, exit_code=0):
        # Exercise the actual check command runner in an isolated repository fixture.
        return runtime.run_check(self.repo, [sys.executable, "-B", "-c", f"raise SystemExit({exit_code})"])

    def promoted(self):
        self.candidate()
        self.assertEqual(self.accept(), 0)
        manifest = runtime.promote(self.repo, self.baseline)
        root, verified = runtime.verify_stable(self.workspace, "MonakaVR")
        self.assertEqual(manifest["generation"], verified["generation"])
        return root, manifest

    def test_exact_accepted_candidate_promotes_with_source_and_hash(self):
        root, manifest = self.promoted()
        self.assertEqual((root / "app.exe").read_bytes(), b"selected application")
        self.assertEqual(manifest["source"], self.source)
        self.assertTrue(manifest["promotion_source"])
        self.assertEqual(manifest['rollback_source']['manifest'], 'rollback-baseline/manifest.json')
        self.assertFalse(list((self.workspace / "runtime/stable").glob(".staging-*")))

    def test_new_build_refuses_old_stable_without_implicit_promotion(self):
        root, _ = self.promoted()
        self.candidate(b"new unpromoted application")
        self.assertEqual((root / "app.exe").read_bytes(), b"selected application")
        with self.assertRaisesRegex(build.ContractError, "STALE_RUNTIME"):
            runtime.verify_stable(self.workspace, "MonakaVR")

    def test_missing_provenance_or_failed_check_never_promotes(self):
        self.candidate()
        with self.assertRaises(build.ContractError):
            runtime.promote(self.repo)
        self.assertEqual(self.accept(7), 7)
        with self.assertRaisesRegex(build.ContractError, "Focused checks"):
            runtime.promote(self.repo)
        self.assertFalse((self.workspace / "runtime/stable/MonakaVR").exists())

    def test_first_promotion_requires_preserved_rollback_reference(self):
        self.candidate()
        self.accept()
        with self.assertRaisesRegex(build.ContractError, 'rollback manifest'):
            runtime.promote(self.repo)
        self.assertFalse((self.workspace / 'runtime/stable/MonakaVR').exists())

    def test_changed_binary_and_changed_manifest_are_both_rejected(self):
        root, manifest = self.promoted()
        (root / "app.exe").write_bytes(b"unexpected application")
        with self.assertRaisesRegex(build.ContractError, "RUNTIME_PROVENANCE_MISMATCH"):
            runtime.verify_stable(self.workspace, "MonakaVR")
        manifest["payload"][0]["sha256"] = build.digest(root / "app.exe")
        manifest["payload"][0]["bytes"] = (root / "app.exe").stat().st_size
        build.write_json(root / "runtime-manifest.json", manifest)
        with self.assertRaisesRegex(build.ContractError, "promoted manifest changed"):
            runtime.verify_stable(self.workspace, "MonakaVR")

    def test_changed_source_during_build_has_no_promotable_manifest(self):
        snapshot = build.begin_build(self.repo)
        self.source["commit"] = "c" * 40
        with self.assertRaisesRegex(build.ContractError, "Source changed"):
            build.finish_build(self.repo, snapshot, [("missing", "app.exe")])
        self.assertFalse(build.manifest_path(self.repo, "windows").exists())

    def test_promotion_lock_refuses_launch_and_path_escape_is_rejected(self):
        self.promoted()
        locked = self.workspace / "runtime/.promotion-MonakaVR.lock"
        locked.write_text("test")
        with self.assertRaisesRegex(build.ContractError, "promotion is in progress"):
            runtime.verify_stable(self.workspace, "MonakaVR")
        for path in ("../elsewhere.exe", str(self.workspace / "outside.exe"), "."):
            with self.assertRaises(build.ContractError):
                build.inside(self.workspace / "runtime/stable/MonakaVR", path)

    def test_failed_atomic_replace_restores_old_tree_without_opening_new_generation(self):
        root, _ = self.promoted()
        self.candidate(b"next application")
        self.accept()
        original = runtime.os.replace

        def fail_stage(source, destination):
            if Path(source).name.startswith(".staging-") and Path(destination) == root:
                raise OSError("injected directory replacement failure")
            return original(source, destination)

        with patch.object(runtime.os, "replace", side_effect=fail_stage):
            with self.assertRaisesRegex(OSError, "injected"):
                runtime.promote(self.repo)
        self.assertEqual((root / "app.exe").read_bytes(), b"selected application")
        self.assertFalse(list((self.workspace / "runtime/stable").glob(".staging-*")))
        with self.assertRaisesRegex(build.ContractError, "STALE_RUNTIME"):
            runtime.verify_stable(self.workspace, "MonakaVR")

    def test_source_selection_change_cannot_promote_an_old_candidate(self):
        self.candidate()
        self.accept()
        self.source["commit"] = "c" * 40
        runtime.select_source(self.repo, ["windows"])
        with self.assertRaises(build.ContractError):
            runtime.promote(self.repo)

    def test_build_records_source_without_a_runtime_catalog(self):
        build.catalog_path(self.workspace).unlink()
        artifact = self.repo / 'Build/app.exe'
        artifact.parent.mkdir()
        artifact.write_bytes(b'candidate')
        snapshot = build.begin_build(self.repo, 'android')
        result = build.finish_build(self.repo, snapshot, [('Build/app.exe', 'app.exe')])
        self.assertEqual(result['source'], self.source)
        self.assertEqual(result['channel'], 'android')
        self.assertEqual(build.read_json(build.manifest_path(self.repo, 'android')), result)
        self.assertFalse(build.catalog_path(self.workspace).exists())

    def test_build_does_not_read_or_mutate_runtime_selection(self):
        artifact = self.repo / 'Build/app.exe'
        artifact.parent.mkdir()
        artifact.write_bytes(b'candidate')
        snapshot = build.begin_build(self.repo)
        catalog = build.load_catalog(self.workspace)
        catalog['products']['MonakaVR']['selected_source']['commit'] = 'c' * 40
        build.write_json(build.catalog_path(self.workspace), catalog)
        result = build.finish_build(self.repo, snapshot, [('Build/app.exe', 'app.exe')])
        self.assertEqual(result['source'], self.source)
        self.assertEqual(build.load_catalog(self.workspace), catalog)

    def test_launcher_metadata_failure_restores_previous_entries(self):
        self.promoted()
        templates = self.repo / 'scripts/workspace_runtime/launchers/_internal'
        templates.mkdir(parents=True)
        (templates.parent / 'Start.cmd').write_text('new')
        for name in ('build_provenance.py', 'runtime_contract.py', 'stable_launcher.py'):
            (templates.parents[1] / name).write_text('# fixture')
        target = self.workspace / 'launchers'
        target.mkdir()
        (target / 'Start.cmd').write_text('old')
        with patch.object(runtime, 'write_json', side_effect=OSError('metadata failure')):
            with self.assertRaisesRegex(OSError, 'metadata failure'):
                runtime.install_launchers(self.repo)
        self.assertEqual((target / 'Start.cmd').read_text(), 'old')
        self.assertFalse(list(self.workspace.glob('.launchers-staging-*')))

    def test_rejected_runtime_never_starts_a_process(self):
        self.promoted()
        self.candidate(b'unpromoted')
        with patch.object(launcher.subprocess, 'Popen') as start:
            self.assertEqual(launcher.main('MonakaVR', self.workspace, []), 2)
            start.assert_not_called()

    def test_native_launch_entries_preserve_managed_owners(self):
        cases = {
            'MonakaBridge': ['Release/monaka_bridge_service.exe',
                             'control/Release/monaka_bridge_control.exe', 'Release/monaka_bridge_config.exe'],
            'PicoMotionTrackerBridge': ['control/Release/pico_ot_steamvr_control.exe',
                                       'Release/pico_ot_bridge_service.exe'],
        }
        for product, paths in cases.items():
            with self.subTest(product=product):
                root = self.workspace / product / 'Build/runtime'
                for relative in paths:
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b'fixture')
                if product == 'MonakaBridge':
                    config = self.workspace / product / 'config/bridge.json'
                    config.parent.mkdir(parents=True)
                    config.write_text(json.dumps({'namespace': 'monaka.bridge', 'version': 2}))
                manifest = {'generation': 'fixture', 'source': self.source,
                            'payload': [{'path': path} for path in paths]}
                spec = launcher.launch_spec(self.workspace, product, root, manifest)
                self.assertEqual(Path(spec['argv'][0]), root / paths[0])
                self.assertIn('--native-owner' if product == 'MonakaBridge' else '--service-exe', spec['argv'])
                self.assertTrue(Path(spec['argv'][0]).is_relative_to(root))

    def test_normal_launch_uses_fixed_runtime_without_catalog(self):
        product = 'PicoMotionTrackerBridge'
        fixed = self.workspace / product / 'Build/runtime'
        for relative in ('control/Release/pico_ot_steamvr_control.exe', 'Release/pico_ot_bridge_service.exe'):
            target = fixed / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'fixed fixture')
        build.catalog_path(self.workspace).unlink()
        with patch.object(launcher.subprocess, 'Popen') as start:
            start.return_value.wait.return_value = 7
            self.assertEqual(launcher.main(product, self.workspace, []), 7)
            self.assertEqual(Path(start.call_args.args[0][0]), fixed / 'control/Release/pico_ot_steamvr_control.exe')
        self.assertFalse(build.catalog_path(self.workspace).exists())

        (fixed / 'control/Release/pico_ot_steamvr_control.exe').unlink()
        old = self.workspace / 'runtime/stable' / product
        for relative in ('control/Release/pico_ot_steamvr_control.exe', 'Release/pico_ot_bridge_service.exe'):
            target = old / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'old fixture')
        with patch.object(launcher.subprocess, 'Popen') as start:
            self.assertEqual(launcher.main(product, self.workspace, []), 2)
            start.assert_not_called()

    def test_bridge_c3_configuration_uses_fixed_native_owner(self):
        product = 'MonakaBridge'
        repo = self.workspace / product
        runtime = repo / 'Build/runtime'
        config = repo / 'config/bridge.json'
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({'namespace': 'monaka.bridge', 'version': 3}))
        for relative in ('Release/monaka_bridge_service.exe',
                         'control/Release/monaka_bridge_control.exe',
                         'Release/monaka_bridge_config.exe'):
            payload = runtime / relative
            payload.parent.mkdir(parents=True, exist_ok=True)
            payload.write_bytes(b'fixture')

        with patch.object(launcher.subprocess, 'Popen') as start:
            self.assertEqual(launcher.main(product, self.workspace, ['--check']), 0)
            start.assert_not_called()
            command = launcher.launch_spec(self.workspace, product, runtime)['argv']
            self.assertEqual(command[:3], [str(runtime / 'Release/monaka_bridge_service.exe'),
                                           '--native-owner', str(config)])

    def test_monaka_profile_is_directory_and_arguments_are_preserved(self):
        fixed = self.repo / 'Build/runtime/win-unpacked'
        fixed.mkdir(parents=True)
        for name in ('SlimeVR.exe', 'slimevr.jar'):
            (fixed / name).write_bytes(b'fixture')
        profile = self.workspace / 'new-profile'
        with patch.object(launcher, 'java_executable', return_value=self.workspace / 'jdk/bin/java.exe'):
            spec = launcher.launch_spec(self.workspace, 'MonakaVR', fixed.parent, profile=profile, mtp=True, port=21111)
            self.assertEqual(spec['argv'][-3:], ['--profile', str(profile), '--monaka-mtp'])
            self.assertIn('21111', spec['argv'])
            self.assertFalse(profile.exists())
            profile.mkdir()
            launcher.launch_spec(self.workspace, 'MonakaVR', fixed.parent, profile=profile)
            file_profile = profile / 'vrconfig.yml'
            file_profile.write_text('fixture')
            with self.assertRaisesRegex(build.ContractError, 'absolute directory'):
                launcher.launch_spec(self.workspace, 'MonakaVR', fixed.parent, profile=file_profile)


if __name__ == "__main__":
    unittest.main()
