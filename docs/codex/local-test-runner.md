# Local revision test runner contract

This document defines the low-context local test workflow for the coordinated five-repository MonakaVR revision. It does not change runtime behavior or replace repository-native build/test logic.

## Canonical per-repository entry point

When `scripts/test_revision.ps1` is present in a checked-out repository, use it as the preferred local orchestration wrapper:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_revision.ps1 -Mode Quick
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_revision.ps1 -Mode Full
```

`Quick` is for the edit/fix loop. `Full` is for a software milestone/checkpoint. The wrapper only calls the repository's existing CMake/CTest/Gradle/Python checks; semantic test logic remains in the native test suites.

Each invocation creates a timestamped directory under `build/revision-test-results/`, writes full stdout/stderr to per-step log files, and writes `summary.json`. Console output is intentionally one concise result line.

## Codex context rule

- On PASS, read only the console result line or `summary.json`. Do not ingest full logs.
- On FAIL, read `summary.json`, then only the failed step log and preferably only the failure vicinity/tail.
- Do not read unrelated successful logs merely to prove that a PASS occurred.
- Do not rewrite the runner for one-off experiments. If the canonical runner is wrong, change it explicitly and separately from production/runtime changes.

## Safety / scope

The runner must not perform `git reset`, `git clean`, rebase, amend, force push, branch deletion, source deletion, package installation, registry/service/driver changes, SteamVR driver registration, or physical-device control.

Hardware/HMD/SteamVR runtime/PICO/VIVE physical validation remains `NOT RUN` unless separately and explicitly executed. A software PASS must never be upgraded to a hardware PASS.

## Workspace orchestration

The five-repository aggregate runner belongs at the local workspace root, outside the five repositories. It should invoke each repository's `scripts/test_revision.ps1` and aggregate only their `summary.json` files. Do not force the workspace orchestrator into an arbitrary product repository.

Recommended order for a full coordinated checkpoint:

1. MonakaProtocol
2. PicoMotionTrackerBridge
3. ViveUltimateTrackerBridge
4. MonakaBridge
5. MonakaVR

Stop-on-first-failure is preferred for the normal Codex loop to avoid wasting compute/context on downstream failures caused by an upstream break. A later human-driven full checkpoint may choose to collect all failures.

## Integration with active Architecture Revision branches

The local runner preparation may live on a dedicated test branch while implementation continues on `refactor/monaka-layer-separation`. Before using the runner against a newer implementation HEAD, bring only the runner/document changes forward with a normal merge/cherry-pick or recreate the small non-runtime commit. Do not rebase/reset/rewrite active implementation history merely to obtain the runner.
