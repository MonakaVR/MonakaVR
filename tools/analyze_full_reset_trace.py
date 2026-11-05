"""Read-only Full Reset trace extraction; no product/runtime dependencies."""
import argparse
import json
import math
from pathlib import Path

FRAME = "monaka.same_frame.v1"
EVENT = "monaka.full_reset.v1"
OWNER = "HUMAN_SKELETON_FULL_RESET"
U63_MAX = (1 << 63) - 1
MIN_WINDOW_NS = 2_000_000_000
Q_FIELDS = ("rotation_xyzw", "usable_rotation_xyzw", "raw_rotation_xyzw",
            "reference_adjusted_rotation_xyzw", "identity_adjusted_rotation_xyzw")


def integer(value, field):
    """Do not round tick/time/generation through a binary64 float."""
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ValueError(f"{field}: expected decimal integer")
    if isinstance(value, str) and (not value or not value.isascii() or not value.isdecimal()):
        raise ValueError(f"{field}: expected unsigned decimal integer")
    result = int(value)
    if not 0 <= result <= U63_MAX:
        raise ValueError(f"{field}: outside U63 range")
    return result


def angular_delta_degrees(a, b):
    """Hamilton xyzw orientation distance; q and -q represent the same rotation."""
    def unit(q):
        if not isinstance(q, list) or len(q) != 4:
            return None
        if any(isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x) for x in q):
            return None
        norm = math.hypot(*q)
        return [x / norm for x in q] if norm > 0 and math.isfinite(norm) else None
    left, right = unit(a), unit(b)
    if left is None or right is None:
        return None
    # Euclidean chord is more stable near zero than acos(dot).
    chord = min(math.dist(left, right), math.dist(left, [-x for x in right]))
    return math.degrees(4 * math.asin(min(1.0, chord / 2)))


def read_records(path):
    records = []
    with Path(path).open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line, parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
            except (ValueError, json.JSONDecodeError) as exc:
                raise ValueError(f"line {line_number}: malformed/truncated JSON: {exc}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"line {line_number}: expected object")
            records.append(record)
    return records


def snapshot(record, index, target):
    def rows(field, predicate):
        values = record.get(field, [])
        if not isinstance(values, list):
            raise ValueError(f"{field}: expected array")
        return [value for value in values if isinstance(value, dict) and predicate(value)]
    bones = {target, "UPPER_CHEST", "CHEST_TRACKER"} if target == "CHEST" else {target, target + "_TRACKER"}
    # Existing computed chest Tracker is assigned UPPER_CHEST, unlike consumed CHEST.
    computed_targets = {"CHEST", "UPPER_CHEST"} if target == "CHEST" else {target}
    return {
        "record_index": index, "tick": record["tick"], "system_ns": record["system_ns"],
        "reset_generation": record["reset_generation"], "reset_phase": record.get("reset_phase"),
        "A_input_trackers": rows("input_trackers", lambda x: x.get("target") == target),
        "B_consumed_rotation_inputs": rows("consumed_rotation_inputs", lambda x: x.get("target") == target),
        "C_bones": rows("bones_after", lambda x: x.get("bone") in bones),
        "C_computed_trackers": rows("computed_trackers", lambda x: x.get("target") in computed_targets),
    }


def deltas(before, after):
    result = {}
    for layer in ("A_input_trackers", "B_consumed_rotation_inputs", "C_bones", "C_computed_trackers"):
        def key(row):
            return (row.get("identity"), row.get("target"), row.get("bone"), row.get("read_site"), row.get("orientation_source"))
        previous = {}
        for row in before[layer]:
            previous.setdefault(key(row), []).append(row)
        current = {}
        for row in after[layer]:
            current.setdefault(key(row), []).append(row)
        entries = []
        for identity in sorted(previous.keys() | current.keys(), key=str):
            left, right = previous.get(identity, []), current.get(identity, [])
            entry = {"identity": identity[0], "target": identity[1], "bone": identity[2],
                     "read_site": identity[3], "orientation_source": identity[4],
                     "before_read_count": len(left), "after_read_count": len(right)}
            def unique_values(rows):
                unique = {}
                for row in rows:
                    observed = {field: row.get(field) for field in Q_FIELDS + ("has_position", "has_rotation") if field in row}
                    unique.setdefault(json.dumps(observed, sort_keys=True), row)
                return list(unique.values())
            left, right = unique_values(left), unique_values(right)
            if len(left) != 1 or len(right) != 1:
                entry["comparison"] = "MISSING_OR_AMBIGUOUS_IDENTITY"
            else:
                entry["angle_degrees"] = {
                    field: angular_delta_degrees(left[0].get(field), right[0].get(field))
                    for field in Q_FIELDS if field in left[0] or field in right[0]
                }
                entry["comparison"] = "AVAILABLE" if any(x is not None for x in entry["angle_degrees"].values()) else "ROTATION_UNAVAILABLE"
            entries.append(entry)
        result[layer] = entries
    return result


def analyze(records, later_frames=20, target="CHEST"):
    if isinstance(later_frames, bool) or not isinstance(later_frames, int) or later_frames < 1:
        raise ValueError("later_frames must be a positive integer")
    report = {
        "schema": "monaka.full_reset.analysis.v1", "status": "BLOCKED", "target": target,
        "later_frames": later_frames, "issues": [], "resets": [],
        "interpretation": "Facts and sign-invariant angular deltas only; no Reset functional PASS or CASE classification. MVIP admitted input is not a GUI orientation display or necessarily reset-mutable.",
    }
    frames, events, ends, transitions = [], {}, [], []
    last_tick = last_generation = last_system_ns = None
    for index, record in enumerate(records):
        schema = record.get("schema")
        if schema == FRAME:
            generation = integer(record.get("reset_generation"), "frame.reset_generation")
            tick = integer(record.get("tick"), "frame.tick")
            system_ns = integer(record.get("system_ns"), "frame.system_ns")
            if record.get("system_clock_domain") != "system_nanotime":
                raise ValueError("frame system clock domain is unproven")
            if last_tick is not None and tick <= last_tick:
                raise ValueError("frame ticks must increase")
            if last_generation is not None and generation < last_generation:
                raise ValueError("frame reset_generation regressed")
            if last_system_ns is not None and system_ns < last_system_ns:
                raise ValueError("frame system time regressed")
            if last_generation is not None and generation != last_generation:
                transitions.append((last_generation, generation))
            frames.append((index, generation, record))
            last_tick, last_generation = tick, generation
            last_system_ns = system_ns
        elif schema == EVENT:
            generation = integer(record.get("reset_generation"), "event.reset_generation")
            integer(record.get("system_ns"), "event.system_ns")
            integer(record.get("started_system_ns"), "event.started_system_ns")
            if (record.get("event") != "FULL_RESET" or record.get("reset_type") != "FULL"
                    or record.get("owner") != OWNER or record.get("clock_domain") != "system_nanotime"):
                raise ValueError("Full Reset event has unsupported type/owner")
            phase = record.get("phase")
            if phase not in ("STARTED", "COMPLETED", "FAILED"):
                raise ValueError("unsupported Full Reset phase")
            phases = events.setdefault(generation, {})
            if phase in phases:
                raise ValueError("duplicate Full Reset generation/phase")
            phases[phase] = (index, record)
        elif schema == "monaka.capture_end.v1":
            ends.append((index, record))
    if not events:
        report["issues"].append("NO_SERVER_FULL_RESET_EVENT; generation/time alone cannot prove a boundary")
        return report
    for previous, current in transitions:
        if current != previous + 1 or current not in events:
            report["issues"].append("UNPROVEN_GENERATION_TRANSITION")
    for generation, phases in events.items():
        item = {"reset_generation": str(generation), "status": "BLOCKED", "issues": []}
        report["resets"].append(item)
        item["events"] = [{"record_index": index, **record} for index, record in sorted(phases.values())]
        if "FAILED" in phases or "STARTED" not in phases or "COMPLETED" not in phases:
            item["issues"].append("FULL_RESET_NOT_PROVEN_COMPLETED")
            continue
        start_index, start = phases["STARTED"]
        end_index, end = phases["COMPLETED"]
        started = integer(start["started_system_ns"], "started_system_ns")
        completed = integer(end.get("completed_system_ns"), "completed_system_ns")
        if not (start_index < end_index and integer(start["system_ns"], "start.system_ns") == started
                and integer(end["started_system_ns"], "end.started_system_ns") == started
                and integer(end["system_ns"], "end.system_ns") == completed and completed >= started):
            item["issues"].append("INCONSISTENT_SERVER_EVENT_BOUNDARY")
            continue
        pre = [(i, r) for i, g, r in frames if i < start_index and g == generation - 1]
        post = [(i, r) for i, g, r in frames if i > end_index and g == generation]
        if not pre or not post:
            item["issues"].append("MISSING_PRE_OR_POST_GENERATION_FRAME")
            continue
        post_index, first = post[0]
        if not (first.get("last_reset_type") == "FULL" and first.get("reset_phase") == "COMPLETED"
                and integer(first.get("last_reset_system_ns"), "last_reset_system_ns") == started
                and integer(first.get("last_reset_completed_system_ns"), "last_reset_completed_system_ns") == completed
                and integer(pre[-1][1]["system_ns"], "pre.system_ns") <= started
                and integer(first["system_ns"], "post.system_ns") >= completed):
            item["issues"].append("FRAME_EVENT_LINK_MISMATCH")
            continue
        item["pre"] = snapshot(pre[-1][1], pre[-1][0], target)
        item["first_post"] = snapshot(first, post_index, target)
        item["pre_tick"], item["post_tick"] = item["pre"]["tick"], first["tick"]
        item["reset_event_index"] = start_index
        item["first_post_delta"] = deltas(item["pre"], item["first_post"])
        item["status"] = "COMPLETE"
        pre_span = started - integer(pre[0][1]["system_ns"], "pre.system_ns")
        post_span = integer(post[-1][1]["system_ns"], "post.system_ns") - completed
        item["observed_window"] = {"pre_ns": str(pre_span), "post_ns": str(post_span),
                                   "minimum_each_ns": str(MIN_WINDOW_NS),
                                   "pre_frames": len(pre), "post_frames": len(post)}
        if pre_span < MIN_WINDOW_NS or post_span < MIN_WINDOW_NS:
            item["status"] = "PARTIAL"
            item["issues"].append("INSUFFICIENT_OBSERVED_PRE_OR_POST_WINDOW")
        if len(post) <= later_frames:
            item["status"] = "PARTIAL"
            item["issues"].append("INSUFFICIENT_LATER_FRAMES")
            item["later"] = None
        else:
            i, record = post[later_frames]
            item["later"] = snapshot(record, i, target)
            item["later_delta"] = deltas(item["pre"], item["later"])
        for name in ("pre", "first_post", "later"):
            sample = item.get(name)
            if sample is not None:
                for layer in ("A_input_trackers", "B_consumed_rotation_inputs", "C_bones", "C_computed_trackers"):
                    if not sample[layer]:
                        item["issues"].append(f"{name}.{layer}: UNAVAILABLE")
                        item["status"] = "PARTIAL"
    report["capture_end"] = ends[-1][1] if ends else None
    if not ends:
        report["issues"].append("NO_CAPTURE_END; capture completeness unproven")
    elif (len(ends) != 1 or ends[-1][0] != len(records) - 1
          or ends[-1][1].get("capture_incomplete") is not False or ends[-1][1].get("failure")
          or integer(ends[-1][1].get("dropped", 0), "capture_end.dropped") != 0):
        report["issues"].append("CAPTURE_INCOMPLETE_OR_FAILURE")
    if ends:
        end = ends[-1][1]
        if (end.get("termination_reason") != "POST_WINDOW_COMPLETE"
                or end.get("capture_profile") != "full_reset_window"
                or integer(end.get("reset_generation"), "capture_end.reset_generation") != next(iter(events))
                or integer(end.get("records_written"), "capture_end.records_written") != len(records) - 1
                or integer(end.get("bytes_written"), "capture_end.bytes_written") <= 0):
            report["issues"].append("CAPTURE_END_REASON_PROFILE_GENERATION_OR_COUNTER_MISMATCH")
        first_reset = report["resets"][0]
        window = first_reset.get("observed_window")
        if window is not None and (end.get("actual_pre_ns") != window["pre_ns"] or end.get("actual_post_ns") != window["post_ns"]):
            report["issues"].append("CAPTURE_END_OBSERVED_WINDOW_MISMATCH")
    report["status"] = ("BLOCKED" if "UNPROVEN_GENERATION_TRANSITION" in report["issues"] or any(x["status"] == "BLOCKED" for x in report["resets"]) else
                        "PARTIAL" if report["issues"] or any(x["status"] == "PARTIAL" for x in report["resets"]) else "COMPLETE")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("--later-frames", type=int, default=20, help="Nth captured frame after first post (default 20)")
    parser.add_argument("--target", default="CHEST")
    args = parser.parse_args()
    try:
        result = analyze(read_records(args.trace), args.later_frames, args.target)
    except (ValueError, OSError) as exc:
        result = {"schema": "monaka.full_reset.analysis.v1", "status": "BLOCKED", "issues": [str(exc)], "resets": []}
    print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
    return 0 if result["status"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())
