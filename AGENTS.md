# MonakaVR repository instructions

This repository owns the vendor-neutral tracking runtime: Generic Tracker ingestion, body assignments, Main Tracker + optional Rotation Fallback policy, existing IK integration, and final virtual tracker output.

## Current coordinated Architecture Revision

The older Task 5 document and its fixed C1/wire-1.0/component-arbitration handoff are historical baseline material for the delivered implementation. For the current coordinated revision, protocol/identity/modality/Main-Fallback semantics are superseded by the Architecture Revision owned in `MonakaVR/MonakaProtocol` (`docs/architecture-revision.md` and `docs/C2.md`).

In particular, do **not** preserve the old behavior merely because existing code implements it: the previous generic per-component competition across all sources is no longer the default policy. The current default is one explicit Main Tracker per body target plus an optional Rotation Fallback Source. Main FULL and usable owns both position and orientation; otherwise position is unavailable and rotation fallback policy applies.

Protocol-dependent migration work must use an **actual generated and hash-pinned wire-v2 kit** from a clean committed MonakaProtocol source tree. Until that artifact and manifest are supplied to this workspace, inspect/plan/refactor contract-independent runtime code freely but stop at the protocol dependency boundary. Do not fabricate hashes, infer v2 fields from v1, or hand-roll a substitute codec.

Where the Architecture Revision does not conflict, existing Task 5 Slime baseline, IK integration, self-feedback exclusion, lifecycle, and hardware-gate constraints remain applicable.

## Codex task entry point

For runtime history and non-conflicting Task 5 constraints, read these files completely before implementation changes:

1. `docs/codex/Task5_MonakaVR.md`
2. `docs/codex/task5/00_upstream_handoff_status.md`

Treat fixed-C1 and old arbitrary component-arbitration sections as historical once the coordinated v2 revision is active. Do not use them to revert the current Architecture Revision.

## Repository-wide constraints

- Work only in `MonakaVR/MonakaVR` unless the active coordinated task explicitly states otherwise.
- Preserve the existing Slime input path and existing IK/SteamVR behavior except for the narrowly required generic Main/Fallback integration changes.
- Do not add PICO API/ABI, POTB, PICO receiver C ABI, VIVE HID/RF, vendor packets, or vendor-specific coordinate transforms to the common runtime.
- Do not independently change shared protocol fields, units, coordinate conventions, version semantics, ports, or codec APIs.
- Use the fixed active MonakaProtocol artifact supplied to this workspace. Do not reimplement or infer the codec/schema when the artifact is missing.
- Treat MonakaBridge output as already mapped/calibrated. Do not apply PICO/VIVE coordinate correction again.
- Main/Fallback assignment is explicit configuration. Do not infer fallback relations from vendor, quality rank, connection order, body role, or matching IDs.
- Preserve stable IK/calibration state across FULL -> ROTATION_ONLY -> fallback -> FULL runtime transitions; modality change alone should not recreate topology unnecessarily.
- Never feed MonakaBridge Direct output, MonakaVR solver output, or virtual tracker output back into the raw Observation/MTP input path. Explicitly test for feedback exclusion.
- Do not remove the active legacy PICO path until the v2 MTP consumer, generic Main/Fallback runtime, body-assignment migration, IK writeback regressions, and existing Slime regressions pass and cutover is explicitly authorized.
- Do not delete branches, files, repositories, or unrelated code. Do not force-push, rebase, amend, reset/rewrite history, or prune remote branches.
- Keep implementation commits small and reviewable.
- Run applicable build/tests before reporting completion. Anything not executed must be reported as `NOT RUN`.
- Do not claim hardware, SteamVR runtime, HMD, PICO, or VIVE validation unless it was actually performed.

## Codex Cloud managed branch exception

Codex Cloud may expose a managed local branch named `work` and may not expose/fetch remote branch refs normally. If the current local branch is `work`, do not fail only because its name differs from `refactor/monaka-layer-separation`.

For the current coordinated revision, continue only after confirming that the checked-out tree/ancestry derives from the prepared `refactor/monaka-layer-separation` lineage. Record the actual local/base state in the final report. Do not silently switch to `main`.

If repository state, the active v2 kit, or historical Task 5 documents conflict with the current Architecture Revision, inspect and report the difference rather than overwriting, discarding work, or reverting to the old policy.
