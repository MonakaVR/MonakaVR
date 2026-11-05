"""O7 independent known trace and missing-evidence negative controls."""
import copy
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import analyze_full_reset_trace as analyzer


def known_trace():
    # Large decimal tick/time values must survive extraction without float rounding.
    base = 9007199254740993
    start, completed = base + 3_000_000_000, base + 3_000_000_010
    def frame(tick, time, generation, q):
        return {"schema": "monaka.same_frame.v1", "tick": str(tick), "system_ns": str(time),
                "system_clock_domain": "system_nanotime", "capture_profile": "full_reset_window",
                "reset_generation": str(generation), "last_reset_type": "FULL" if generation else None,
                "reset_phase": "COMPLETED" if generation else None,
                "last_reset_system_ns": str(start) if generation else None,
                "last_reset_completed_system_ns": str(completed) if generation else None,
                "input_trackers": [{"target": "CHEST", "identity": "opaque-input", "source_domain": "MVIP_ADMITTED_INPUT",
                                    "orientation_source": "MVIP_ACCEPTED_SAMPLE_ORIENTATION", "gui_orientation_available": False,
                                    "gui_orientation_unavailable_reason": "CATALOG_ONLY_NO_ORIENTATION_DISPLAY",
                                    "has_rotation": True, "rotation_xyzw": [0, 0, 0, 1]}],
                "consumed_rotation_inputs": [{"target": "CHEST", "identity": "private-fk-input", "has_position": False,
                                              "has_rotation": True, "read_site": "CHEST", "rotation_xyzw": q}],
                "bones_after": [{"bone": "CHEST", "rotation_xyzw": q}],
                "computed_trackers": [{"target": "UPPER_CHEST", "identity": "computed", "rotation_xyzw": q}]}
    def event(phase, time):
        return {"schema": "monaka.full_reset.v1", "event": "FULL_RESET", "reset_generation": "1", "reset_type": "FULL",
                "phase": phase, "system_ns": str(time), "started_system_ns": str(start),
                "completed_system_ns": str(completed) if phase == "COMPLETED" else None,
                "owner": "HUMAN_SKELETON_FULL_RESET", "clock_domain": "system_nanotime"}
    q90 = [0, math.sqrt(.5), 0, math.sqrt(.5)]
    records = [frame(base, base, 0, [0, 0, 0, 1]),
               frame(base + 1, start - 1, 0, [0, 0, 0, 1]),
               event("STARTED", start), event("COMPLETED", completed)]
    records.extend(frame(base + 2 + i, completed + i * 100_000_000, 1, q90 if i != 1 else [-x for x in q90]) for i in range(31))
    records.append({"schema": "monaka.capture_end.v1", "termination_reason": "POST_WINDOW_COMPLETE",
                    "capture_profile": "full_reset_window", "reset_generation": "1",
                    "actual_pre_ns": "3000000000", "actual_post_ns": "3000000000",
                    "pre_window_ns": "5000000000", "post_window_ns": "5000000000", "sample_interval_ns": "50000000",
                    "capture_incomplete": False, "failure": None, "dropped": "0", "records_written": "35", "bytes_written": "10000"})
    return records


class FullResetAnalysisTests(unittest.TestCase):
    def test_known_boundary_and_factual_layers_preserve_u63(self):
        result = analyzer.analyze(known_trace())
        self.assertEqual(result["status"], "COMPLETE")
        reset = result["resets"][0]
        self.assertEqual(reset["reset_event_index"], 2)
        self.assertEqual(reset["pre_tick"], "9007199254740994")
        self.assertEqual(reset["post_tick"], "9007199254740995")
        self.assertEqual(reset["later"]["tick"], "9007199254741015")
        self.assertEqual(reset["observed_window"]["pre_ns"], "3000000000")
        self.assertEqual(reset["observed_window"]["post_ns"], "3000000000")
        delta = reset["first_post_delta"]
        self.assertEqual(delta["A_input_trackers"][0]["angle_degrees"]["rotation_xyzw"], 0)
        for layer in ("B_consumed_rotation_inputs", "C_bones", "C_computed_trackers"):
            self.assertAlmostEqual(delta[layer][0]["angle_degrees"]["rotation_xyzw"], 90)
        self.assertFalse(reset["first_post"]["A_input_trackers"][0]["gui_orientation_available"])
        self.assertNotIn("case", result)

    def test_sign_invariant_and_invalid_quaternions(self):
        q = [0, math.sqrt(.5), 0, math.sqrt(.5)]
        self.assertEqual(analyzer.angular_delta_degrees(q, [-x for x in q]), 0)
        self.assertAlmostEqual(analyzer.angular_delta_degrees([0, 0, 0, 2], q), 90)
        for invalid in (None, [0, 0, 0, 0], [0, 0, math.nan, 1], [False, 0, 0, 1]):
            self.assertIsNone(analyzer.angular_delta_degrees(q, invalid))

    def test_repeated_actual_fk_reads_keep_counts_and_reject_differing_values(self):
        records = known_trace()
        for record in records:
            if record.get("schema") == "monaka.same_frame.v1":
                record["consumed_rotation_inputs"] *= 8
        result = analyzer.analyze(records)
        delta = result["resets"][0]["first_post_delta"]["B_consumed_rotation_inputs"][0]
        self.assertEqual((delta["before_read_count"], delta["after_read_count"]), (8, 8))
        self.assertAlmostEqual(delta["angle_degrees"]["rotation_xyzw"], 90)
        records[4]["consumed_rotation_inputs"] = [copy.deepcopy(row) for row in records[4]["consumed_rotation_inputs"]]
        records[4]["consumed_rotation_inputs"][0]["rotation_xyzw"] = [0, 0, 0, 1]
        delta = analyzer.analyze(records)["resets"][0]["first_post_delta"]["B_consumed_rotation_inputs"][0]
        self.assertEqual(delta["comparison"], "MISSING_OR_AMBIGUOUS_IDENTITY")

    def test_no_event_missing_event_failed_or_bad_owner_refused(self):
        original = known_trace()
        for removed in ({2, 3}, {2}, {3}):
            result = analyzer.analyze([r for i, r in enumerate(original) if i not in removed])
            self.assertEqual(result["status"], "BLOCKED")
        failed = copy.deepcopy(original)
        failed[3]["phase"] = "FAILED"
        self.assertEqual(analyzer.analyze(failed)["status"], "BLOCKED")
        wrong = copy.deepcopy(original)
        wrong[2]["owner"] = "GUI_CLICK"
        with self.assertRaisesRegex(ValueError, "owner"):
            analyzer.analyze(wrong)

    def test_link_mismatch_and_legacy_trace_refused(self):
        records = known_trace()
        records[4]["last_reset_system_ns"] = "1"
        self.assertEqual(analyzer.analyze(records)["status"], "BLOCKED")
        with self.assertRaisesRegex(ValueError, "reset_generation"):
            analyzer.analyze([{"schema": "monaka.same_frame.v1", "tick": "1", "system_ns": "1"}])

    def test_later_frames_early_reset_early_close_drop_and_missing_layer_partial(self):
        records = known_trace()
        self.assertEqual(analyzer.analyze(records, later_frames=40)["status"], "PARTIAL")
        early = copy.deepcopy(records)
        early[0]["system_ns"] = str(int(early[2]["system_ns"]) - 1_000_000_000)
        self.assertEqual(analyzer.analyze(early)["status"], "PARTIAL")
        self.assertEqual(analyzer.analyze(records[:20] + [records[-1]])["status"], "PARTIAL")
        self.assertEqual(analyzer.analyze(records[:-1])["status"], "PARTIAL")
        for field, value in (("capture_incomplete", True), ("dropped", "1"), ("failure", "I/O failure")):
            changed = copy.deepcopy(records)
            changed[-1][field] = value
            self.assertEqual(analyzer.analyze(changed)["status"], "PARTIAL")
        records[4]["input_trackers"] = []
        self.assertEqual(analyzer.analyze(records)["status"], "PARTIAL")

    def test_truncated_cli_json_is_blocked_and_source_is_unchanged(self):
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".ndjson",
                                         dir=Path(__file__).resolve().parent, delete=False) as stream:
            path = Path(stream.name)
            content = '\n'.join(json.dumps(r) for r in known_trace()) + '\n{"schema":'
            stream.write(content)
        try:
            process = subprocess.run([sys.executable, str(Path(analyzer.__file__)), str(path)], capture_output=True, text=True)
            self.assertEqual(process.returncode, 2)
            self.assertEqual(json.loads(process.stdout)["status"], "BLOCKED")
            self.assertEqual(path.read_text(encoding="utf-8"), content)
        finally:
            path.unlink()

    def test_invalid_float_integer_and_unproven_transition_refused(self):
        records = known_trace()
        records[4]["tick"] = float(records[4]["tick"])
        with self.assertRaises(ValueError):
            analyzer.analyze(records)
        records = known_trace()
        records[-2]["reset_generation"] = "2"
        self.assertEqual(analyzer.analyze(records)["status"], "BLOCKED")

    def test_end_reason_profile_generation_counters_and_clock_domain(self):
        for field, value in (("termination_reason", "EXPLICIT_CLOSE"), ("termination_reason", "UNKNOWN"),
                             ("capture_profile", "detailed"), ("reset_generation", "2"),
                             ("records_written", "0"), ("bytes_written", "0"), ("actual_post_ns", "1")):
            records = known_trace()
            records[-1][field] = value
            self.assertEqual(analyzer.analyze(records)["status"], "PARTIAL")
        records = known_trace()
        records[4]["system_clock_domain"] = "remote_clock"
        with self.assertRaisesRegex(ValueError, "clock domain"):
            analyzer.analyze(records)


if __name__ == "__main__":
    unittest.main()
