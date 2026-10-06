"""Executable-level safety/log checks. Never initializes OpenVR or invokes resets."""
import json
from pathlib import Path
import subprocess
import sys


def main():
    executable = str(Path(sys.argv[1]).resolve(strict=True))

    def run(*args):
        return subprocess.run([executable, *args], capture_output=True, text=True,
                              encoding="utf-8", timeout=10, check=False)

    for args, message in [
        ((), "explicit --action required"),
        (("--action", "invalid"), "invalid action"),
        (("--action", "seated-reset"), "confirmation"),
        (("--action", "standing-reset"), "confirmation"),
        (("--action", "mark"), "nonempty --label"),
    ]:
        result = run(*args)
        assert result.returncode == 2, (args, result.returncode, result.stderr)
        # The real error says the precise required flag for persistent changes.
        if message == "confirmation":
            assert "--allow-persistent-chaperone-change required" in result.stderr
        else:
            assert message in result.stderr
        assert not result.stdout, (args, "must fail before action records/runtime")

    result = run("--help", "--action", "seated-reset")
    assert result.returncode == 0 and "saved zero pose" in result.stdout
    assert "MONAKA_HIL_ACTION_V1 {" not in result.stdout

    label = 'tracking_cover_begin\n"\\\t'
    owners = set()
    ids = set()
    for _ in range(2):
        result = run("--action", "mark", "--label", label)
        assert result.returncode == 0, result.stderr
        rows = []
        for line in result.stdout.splitlines():
            prefix = "MONAKA_HIL_ACTION_V1 "
            assert line.startswith(prefix), line
            rows.append(json.loads(line[len(prefix):]))
        assert [row["kind"] for row in rows] == ["request", "result"]
        request, returned = rows
        assert request["action_id"] == returned["action_id"]
        assert request["helper_owner"] == returned["helper_owner"]
        assert request["action_id"] not in ids
        assert request["helper_owner"] not in owners
        ids.add(request["action_id"])
        owners.add(request["helper_owner"])
        for row in rows:
            assert row["label"] == label
            assert row["action_ordinal"] == 1
            assert row["action_kind"] == "mark" and row["origin_kind"] == "none"
            assert row["frame_identity"] is False and row["physical_application_time"] is False
            assert isinstance(row["local_wall_time"], str) and int(row["local_wall_time"]) > 0
            assert int(row["qpc_ticks"]) > 0 and row["qpc_frequency"] > 0
            assert row["local_monotonic_ns"] > 0
        assert request["local_monotonic_ns"] <= returned["local_monotonic_ns"]
        assert int(request["qpc_ticks"]) <= int(returned["qpc_ticks"])
        assert returned["api_status"] == "operator_marker_recorded_no_runtime_action"
    print("PASS: executable guards, no-action help, JSON parser, escaped labels, real unique owners and clocks")


if __name__ == "__main__":
    main()
