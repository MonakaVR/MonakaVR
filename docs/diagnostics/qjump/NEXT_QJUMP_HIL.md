# Next Q-jump capture HIL

Prepared offline on 2026-11-07. This document does not authorize starting applications or hardware. The current authorization ends after implementation, software review, local topic commits and fixed runtime builds. Candidate/build acceptance is recorded separately in `QJUMP_SOFTWARE_ACCEPTANCE.md`; do not use an unaccepted build.

## Purpose and unchanged tracking setup

Capture one naturally occurring jump/residual twist through received native Q, generic Bridge transformations, admitted/consumed Q and bone/computed output. Do not try to reproduce it by repeated violent movements or repeated Reset. Previously passed Full Reset and five-point user-observed tracking are not rerun as a quality audit.

Use the existing five-point Q-only profile: CHEST, left/right upper legs, left/right lower legs, Q Source assigned and 6DoF Source unassigned. Preserve the user's `forceArmsFromHMD=false` setting, which resolved the separate arm twist report. Bridge is the sole normalized output owner (`policy=monaka`); PICO compatibility SteamVR output remains disabled. Do not reset mapping, transform, dimensions or user assignments merely to enable diagnostics. If the existing configuration has subsequently changed, resolve only that concrete discrepancy before starting.

## Diagnostic run

After separate HIL authorization, start all three products through their normal repository-owned fixed `Build/runtime` launchers, with the diagnostic environment inherited from the launching shell. Never point a launcher at a dated package, task worktree or old candidate. Use one new run directory and one common neutral coordination directory. Each collector writes only its own output and the common diagnostic request surface; no component reads another product's configuration or private logs.

Set the following in the same launching shell (replace `<new run>` with one absolute new run directory):

```powershell
$env:MONAKA_QJUMP_DIAGNOSTICS = '1'
$env:MONAKA_QJUMP_DIAGNOSTICS_DIR = '<new run>/monaka'
$env:BRIDGE_QJUMP_DIAGNOSTICS = '1'
$env:BRIDGE_QJUMP_DIAGNOSTICS_DIR = '<new run>/bridge'
$env:PICO_QJUMP_DIAGNOSTICS = '1'
$env:PICO_QJUMP_DIAGNOSTICS_DIR = '<new run>/pico'
$env:QJUMP_CAPTURE_COORDINATION_DIR = '<new run>/coordination'
```

All directory values must be absolute. Keep the default 45-degree threshold; optional overrides are `QJUMP_CAPTURE_STEP_DEGREES` for the two C++ collectors and `MONAKA_QJUMP_THRESHOLD_DEG` for Monaka. They are diagnostic thresholds, never rejection thresholds. Each collector creates a component/process-UUID subdirectory with `status.json` and event UUID `.jsonl` bundles. Accepted candidate provenance is completed in the acceptance record after software verification. Do not start a partial profile with only the consumer enabled: stable or silent upstream participants also need prehistory for a useful diagnosis.

The existing Monaka launcher supports the existing profile and public input without rewriting it:

```text
launchers/Start_PicoMotionTrackerBridge.cmd
launchers/Start_MonakaBridge.cmd
launchers/Start_MonakaVR.cmd --profile <existing absolute Q-only profile> --monaka-mtp
```

The existing dedicated profile is `<workspace>/hil/q-only-fbt-20261105/phase-b/profile`. These are the ordinary launch entries; no product settings or launcher files are rewritten for capture.

Normal launch preserves Java discovery via MONAKA_JAVA/JAVA_HOME/PATH. PICO uses its usual tray owner; Bridge uses its usual native owner. Diagnostic environment changes apply to newly launched processes, not to an already running singleton. Confirm the accepted diagnostic status for each participant once; do not start duplicates.

Require no diagnostic drops/writer errors, non-exhausted status and remaining event capacity (written events below the configured maximum). Monaka can still display ARMED after writing its last permitted event until the next trigger exposes EXHAUSTED; ARMED alone is insufficient. A footer's completed recorder window is distinct from fresh post-event pose data. In particular, a silent upstream can acknowledge a request but cannot prove that its unobserved Q stayed stable. The analyzer must retain that distinction.

Wait at least 10 seconds after all participants are ready so the pre-event rings are filled. If setup requires Full Reset, finish it before ordinary observation and allow its event postwindow to close. Initial lifecycle seeding is not an error. A captured Reset is an intentional lifecycle event, not proof of the reported defect.

Use the ordinary five-point setup normally. On a spontaneous symptom, record local time and the first affected body part. Avoid changing assignment, Mount Reset, Full Reset or restarting until evidence has been saved. Allow at least 10 seconds after the symptom for the configured postwindow; inspect completion/error status once. If a participant reports failure, exhaustion, missing layers or an incomplete event, retain the evidence and report that limitation. Do not silently claim all-layer coverage.

After capture, a single Full Reset remains an allowed minimal recovery only when later HIL authorization permits it and continued use is difficult. It does not erase or repair the original incident evidence.

## User observation

Record observation separately from inference: event time; first affected body; whole body or local; input Q display or only skeleton; transient recovery or persistent residual; residual body parts; preceding Reset/assignment/occlusion/reacquisition. No repeated physical reenactment is required just to fill unknown fields.

## Retention, analysis and stop

Nominal capture is 20 Hz, 10 seconds before and 10 seconds after a trigger. Detection runs before retention thinning. T1 angular step >=45 degrees, T2 fresh persistent offset candidate >=2 seconds, T3 reset/correction, T4 assignment, T5 proxy/topology, T6 session/space; all are diagnostic only. Quaternion sign inversion is not rotation.

G0 upper ceiling: 32 MiB/event, 16 events or 512 MiB/product/run; three products <=1.5 GiB plus <=1 MiB request/status metadata. Actual implementations may use tighter bounds, reported in acceptance. No old events are overwritten or automatically deleted. Exhaustion or writer failure affects capture readiness, not tracking. These are encoded-data bounds, not RAM/RSS measurements.

Run `tools/analyze_qjump_incident.py` offline on explicitly selected completed bundles from all three products. It writes `QJUMP_INCIDENT_ANALYSIS.json` and `.md`. Exact public identity/sequence links and UTC overlap establish supported comparisons. Different process monotonic clocks are not directly compared. Missing links, windows or records must remain `INSUFFICIENT_EVIDENCE`. CASE A–D are layer candidates; CASE E is `CORRELATED_NOT_PROVEN`, not a cause assertion.

Stop through normal product paths: Monaka normal Close, Bridge native owner Exit, PICO tray Exit. A stop before the postwindow ends yields an incomplete capture. Remove diagnostic environment variables from the launching shell when done; do not change product settings or saved transforms. Keep the original event bundles immutable.

## Rollback

Tracking behavior is intended to be unchanged and diagnostics default OFF. Disable the explicit diagnostic environment for the next normal launch when capture is not needed. If the diagnostic candidate itself requires rollback, retain its topic commit and rebuild the requested original base through each repository's normal build entry; no old binary stash or launcher redirection is needed. Original bases are recorded in `QJUMP_CAPTURE_DESIGN.md`. Rollback of a running deployment requires its own applicable authorization and normal shutdown; this offline task does not perform a runtime restart.
