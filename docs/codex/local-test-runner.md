# Local revision test runner contract

This document defines the low-context local test workflow for the coordinated five-repository MonakaVR Architecture Revision. It does not change runtime behavior and does not replace repository-native CMake/CTest/Gradle/Python test logic.

## Canonical per-repository entry point

All five `refactor/monaka-layer-separation` branches provide:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_revision.ps1 -Mode Quick
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test_revision.ps1 -Mode Full
```

`Quick` is for edit/fix loops. `Full` is for a software milestone/checkpoint. Full does **not** mean hardware, interactive SteamVR, release/cutover, or legacy release-evidence generation.

Each invocation creates a timestamped directory under `build/revision-test-results/`, writes full stdout/stderr to per-step log files, and writes `summary.json`. Console output is intentionally one concise result line.

## Current scope by repository

- `MonakaProtocol`
  - Quick: boundaries, C++ build/CTest, JVM build, wire-v2 tests.
  - Full: Quick plus wire-v1 and package/verify for both wire majors.
- `PicoMotionTrackerBridge`
  - Quick: pinned v2 + historical v1 kit verification, Release CMake build, all current backend CTests.
  - Full: Quick plus the independent Monaka C ABI integration build/tests.
  - Historical release extraction/packaging is deliberately outside this runner while F10/source-bound v2 release evidence remains follow-up work.
- `ViveUltimateTrackerBridge`
  - Quick: pinned v2 + historical v1 kit verification, Release build, protocol/observation integrity subset.
  - Full: all current CTests including loopback/cross-language/service checks when available.
- `MonakaBridge`
  - Quick: pinned v2/upstream integrity, Release build and tests with SteamVR driver build disabled.
  - Full: requests the SteamVR driver build and runs the full current CTest set. This requires the repository's already-verified local OpenVR SDK checkout; the runner never downloads or registers it.
  - Interactive SteamVR and historical Task4 release evidence remain outside the runner.
- `MonakaVR`
  - Quick: pinned v2 verification plus Architecture Revision, runtime, resolver/pipeline, Slime modality adapter and candidate policy tests.
  - Full: `:server:core:test :server:desktop:test :server:desktop:shadowJar`.
  - Legacy Task5 release evidence remains outside the runner pending v2 release-tool migration.

## Codex context rule

- On PASS, read only the console result line or `summary.json`; do not ingest full logs.
- On FAIL, read `summary.json`, then only the failed step log, preferably only the failure vicinity/tail.
- Do not read unrelated successful logs merely to prove a PASS.
- Do not rewrite the canonical runner for one-off experiments. If a runner is wrong, change it explicitly in a test/tooling-only commit, separate from production semantics.

## Safety / scope

The runner must not perform:

- `git reset`, `git clean`, rebase, amend, force push, history rewrite, branch/file deletion;
- package installation;
- registry/service/driver changes;
- SteamVR driver registration;
- physical-device/HMD control;
- source-code rewriting.

Hardware/HMD/SteamVR runtime/PICO/VIVE physical validation remains `NOT RUN` unless separately and explicitly executed. A software PASS must never be upgraded to a hardware PASS.

## Workspace orchestration

The five-repository aggregate runner belongs at the local workspace root, outside the five repositories. It should invoke each repository's `scripts/test_revision.ps1` and aggregate only their `summary.json` files.

Recommended order:

1. MonakaProtocol
2. PicoMotionTrackerBridge
3. ViveUltimateTrackerBridge
4. MonakaBridge
5. MonakaVR

Stop-on-first-failure is preferred for the normal Codex loop to avoid wasting compute/context on downstream failures caused by an upstream break. A later human-driven evidence pass may deliberately collect all failures.

## Current checkpoint boundary

The Architecture Revision software integration checkpoint was reached before runner integration. These wrappers are tooling added after that checkpoint; they do not constitute new runtime/software acceptance until executed on the local machine. Full five-repository wire E2E, release-evidence migration, hardware and interactive SteamVR remain separate follow-up gates.
