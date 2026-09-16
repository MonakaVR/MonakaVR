"""Release gate regressions; executable unchanged under python and python -O."""
import ast
import json
from pathlib import Path
import uuid
import unittest
import zipfile

import release_v2 as gate


class ReleaseValidationTests(unittest.TestCase):
    def setUp(self):
        self.root = gate.ROOT / 'build/release-tool-tests' / uuid.uuid4().hex
        self.root.mkdir(parents=True)

    def report(self):
        return {"result": "PASS", "wire_version": gate.WIRE, "source": {"commit": "current"},
                "hardware_validation": "NOT RUN", "steamvr_interactive": "NOT RUN",
                "steps": [{"name": "build", "command": ["cmake", "--build"], "exit_code": 0, "log_sha256": "hash"}],
                "tests": [{"result": "PASS", "tests": 1, "cases": ["identity"]}],
                "artifacts": {"runtime/app": "hash"}, "files_sha256": {"runtime/app": "hash"}}

    def validate(self, report):
        gate.check_receipt(report, {"commit": "current"}, ["build"], [["identity"]])

    def test_valid_receipt(self):
        self.validate(self.report())

    def test_stale_head_and_wrong_wire_fail(self):
        for field, value in (("source", {"commit": "old"}), ("wire_version", {"major": 1, "minor": 0}), ("result", "RUNNING")):
            report = self.report()
            report[field] = value
            with self.assertRaises(RuntimeError):
                self.validate(report)

    def test_missing_steps_nonzero_exit_and_empty_hashes_fail(self):
        for mutate in (
            lambda r: r.update(steps=[]),
            lambda r: r["steps"][0].update(exit_code=1),
            lambda r: r.update(files_sha256={}),
            lambda r: r.update(artifacts={}),
            lambda r: r["tests"][0].update(cases=["unrelated"]),
            lambda r: r.update(tests=[]),
            lambda r: r.update(hardware_validation="PASS"),
        ):
            report = self.report()
            mutate(report)
            with self.assertRaises(RuntimeError):
                self.validate(report)

    def test_junit_empty_failed_skipped_missing_case_fail(self):
        path = self.root / "results.xml"
        for xml in (
            '<testsuite tests="0"/>',
            '<testsuite tests="1" failures="1"><testcase name="identity"><failure/></testcase></testsuite>',
            '<testsuite tests="1"><testcase name="identity"><skipped/></testcase></testsuite>',
            '<testsuite tests="1"><testcase name="other"/></testsuite>',
            '<testsuite tests="2"><testcase name="identity"/></testsuite>',
        ):
            path.write_text(xml)
            with self.assertRaises(RuntimeError):
                gate.check_tests([path], ["identity"])
        path.write_text('<testsuite tests="1"><testcase name="identity"/></testsuite>')
        self.assertEqual(gate.check_tests([path], ["identity"])["tests"], 1)

    def archive(self):
        path = self.root / "release.zip"
        external = gate.write_archive(path, {"runtime/app": b"binary", "evidence/test": b"PASS"}, "current")
        return path, external

    def test_deterministic_archive(self):
        path, external = self.archive()
        second = self.root / "second.zip"
        other = gate.write_archive(second, {"evidence/test": b"PASS", "runtime/app": b"binary"}, "current")
        self.assertEqual(external["sha256"], other["sha256"])
        self.assertEqual(gate.verify_archive(path, external, "current")["runtime/app"], b"binary")

    def test_tampered_zip_and_stale_manifest_fail(self):
        path, external = self.archive()
        for changed in (dict(external, sha256="wrong"), dict(external, source_commit="old"),
                        dict(external, content_manifest_sha256="wrong")):
            with self.assertRaises(RuntimeError):
                gate.verify_archive(path, changed, "current")

    def test_rehashed_zip_with_tampered_member_still_fails(self):
        path, external = self.archive()
        with zipfile.ZipFile(path) as z:
            files = {n: z.read(n) for n in z.namelist()}
        files["runtime/app"] = b"modified"
        with zipfile.ZipFile(path, "w") as z:
            for name, data in files.items():
                z.writestr(name, data)
        external["sha256"] = gate.sha(path.read_bytes())
        with self.assertRaisesRegex(RuntimeError, "member hash"):
            gate.verify_archive(path, external, "current")

    def test_unlisted_member_fails_even_with_new_zip_hash(self):
        path, external = self.archive()
        with zipfile.ZipFile(path, "a") as z:
            z.writestr("extra", b"unexpected")
        external["sha256"] = gate.sha(path.read_bytes())
        with self.assertRaisesRegex(RuntimeError, "Incomplete"):
            gate.verify_archive(path, external, "current")

    def test_unsafe_paths_fail(self):
        for name in ("../escape", "/root", "C:/file", "a\\b", "a/../b", "a//b", ""):
            with self.assertRaises(RuntimeError):
                gate.member(name)

    def test_no_optimization_dependent_gate(self):
        for name in ("release_v2.py", "release_interop.py"):
            tree = ast.parse(Path(__file__).with_name(name).read_text())
            self.assertFalse(any(isinstance(n, ast.Assert) or isinstance(n, ast.Name) and n.id == "__debug__" for n in ast.walk(tree)))

    def test_policy_steps_are_complete_and_unique(self):
        policy = json.loads(Path(__file__).with_name("release_v2.json").read_bytes())
        names = [c["name"] for c in policy["commands"]]
        self.assertEqual(names, policy["required_steps"])
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(policy["artifacts"])
        self.assertTrue(all(g["required"] for g in policy["test_groups"]))



    def kit_fixture(self):
        import io
        files = {"docs/C2.md": b"frozen contract", "codec": b"fixed codec"}
        lock = {"source_commit": "kit-source", "source_tree_clean": True, "wire_version": gate.WIRE,
                "contract_revision_sha256": gate.sha(files["docs/C2.md"]),
                "files_sha256": {n: gate.sha(b) for n, b in files.items()}}
        files["protocol.lock.json"] = gate.encoded(lock)
        files["SHA256SUMS"] = "".join(f"{gate.sha(b)}  {n}\n" for n, b in files.items()).encode()
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as z:
            for name, data in files.items():
                z.writestr(name, data)
        archive = output.getvalue()
        metadata = {"wire_version": gate.WIRE, "source_commit": "kit-source", "schema_commit": "schema",
                    "sha256": gate.sha(archive), "protocol_lock_sha256": gate.sha(files["protocol.lock.json"]),
                    "contract_revision_sha256": lock["contract_revision_sha256"]}
        raw = gate.encoded(metadata)
        pin = dict(metadata, manifest_sha256=gate.sha(raw))
        return archive, raw, pin

    def test_exact_dependency_pin_and_internal_hashes(self):
        archive, raw, pin = self.kit_fixture()
        self.assertEqual(gate.check_kit_bytes(archive, raw, pin)["source_commit"], "kit-source")
        for key in ("sha256", "manifest_sha256", "source_commit", "contract_revision_sha256", "protocol_lock_sha256"):
            changed = dict(pin, **{key: "wrong"})
            with self.assertRaises(RuntimeError):
                gate.check_kit_bytes(archive, raw, changed)
        changed = dict(json.loads(raw), wire_version={"major": 1, "minor": 0})
        with self.assertRaises(RuntimeError):
            gate.check_kit_bytes(archive, gate.encoded(changed))

    def test_dirty_tree_is_not_accepted(self):
        from unittest.mock import patch
        with patch.object(gate, "git", return_value=" M tracked-source"):
            with self.assertRaisesRegex(RuntimeError, "clean"):
                gate.snapshot()

if __name__ == "__main__":
    unittest.main()
