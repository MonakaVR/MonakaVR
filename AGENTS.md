# MonakaVR repository instructions

This repository owns the vendor-neutral tracking runtime: Common Pose, ObservationSourceProfile, Fusion/Constraint/Fallback, body assignments, existing IK integration, and final virtual tracker output.

## Codex task entry point

For the current layer-separation work, read these files completely before implementation changes:

1. `docs/codex/Task5_MonakaVR.md`
2. `docs/codex/task5/00_upstream_handoff_status.md`

Treat them as the normative Task 5 instructions for this work.

## Repository-wide constraints

- Work only in `MonakaVR/MonakaVR`. Do not commit to sibling repositories.
- Preserve the existing Slime input path and existing IK/SteamVR behavior while adding the MTP path.
- Do not add PICO API/ABI, POTB, PICO receiver C ABI, VIVE HID/RF, vendor packets, or vendor-specific coordinate transforms to the common runtime.
- Do not independently change C1 fields, units, coordinate conventions, version semantics, ports, or codec APIs.
- Use the fixed Task 1 protocol artifact supplied to this workspace. Do not reimplement or infer the codec/schema when the artifact is missing.
- Treat MonakaBridge output as already mapped/calibrated. Do not apply PICO/VIVE coordinate correction again in Task 5.
- Never feed MonakaBridge Direct output, MonakaVR solver output, or virtual tracker output back into the raw Observation/MTP input path. Explicitly test for feedback exclusion.
- Do not remove the active legacy PICO path until the MTP consumer, generic runtime, body-assignment migration, Common Pose/Fallback tests, IK writeback test, and existing Slime regressions pass.
- Do not delete branches, files, repositories, or unrelated code. Do not force-push, rebase, amend, reset/rewrite history, or prune remote branches.
- Keep implementation commits small and reviewable.
- Run applicable build/tests before reporting completion. Anything not executed must be reported as `NOT RUN`.
- Do not claim hardware, SteamVR runtime, HMD, PICO, or VIVE validation unless it was actually performed.

## Codex Cloud managed branch exception

Codex Cloud may expose a managed local branch named `work` and may not expose/fetch remote branch refs normally. If the current local branch is `work`, do not fail only because its name differs from `refactor/monaka-layer-separation`.

Continue only after confirming that the checked-out tree/ancestry corresponds to the prepared Task 5 base (`feature/pico-motion-tracker-bridge-backend` at audited commit `eabf9c196c3859007139177bef3191ac83eaf97d`, plus these Task 5 preparation documents). Record the actual local/base state in the final report. Do not silently switch to `main`.

If repository state conflicts with the Task 5 documents, inspect and report the difference rather than overwriting or discarding work.