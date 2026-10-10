# Core PoC local HIL harness — Phase 2C-1

This package runs the real Core ConstraintPipeline/resolver, existing HIP rotation learner,
position correction orchestrator, ConstraintIkWriteback, HumanPoseManager/IKSolver and
OutputContinuityController. It creates no receiver, VRServer, SteamVR bridge or device connection.
Java 17+ and Python 3 are required. No package installation is needed.

The default gate is inactive. `--hil` explicitly enables availability control. Config cannot
silently activate the gate. The real-source template requires the operator to confirm world
alignment and body frames before starting. The synthetic config's assertions apply only to
synthetic data. Source identities, epochs and world fields are never modified by the gate.

## Software replay

Use a new absolute session directory for each independent process:

```powershell
python hil-control.py --session C:/work/poc-session start --config synthetic.config.json --hil
python hil-control.py --session C:/work/poc-session capture-start
python hil-control.py --session C:/work/poc-session replay scenarios/full-cycle.jsonl
python hil-control.py --session C:/work/poc-session status
python hil-control.py --session C:/work/poc-session capture-stop
python hil-control.py --session C:/work/poc-session shutdown
python analyze-capture.py C:/work/poc-session/capture.jsonl
```

`generate-scenarios.py --out scenarios` regenerates seven deterministic inputs. Other scenarios:
short-dropout, chatter, dwell-reloss, blend-reloss, large-residual and small-residual.
No dropout debounce is present: every loss enters fallback; recovery always needs a fresh
150ms physical FULL sample span. Defaults are existing/provisional software values, not hardware tuning.

## Machine-callable control

`status`, `capture-start --name NAME.jsonl`, `capture-stop`, `6dof-valid on|off`, `mark NOTE`, `shutdown`.
`input FRAME.json` and `replay FILE.jsonl` feed source observations; they are not transition commands.
Control replies do not change state until the next observation tick. Capture paths are confined to
the session directory. Reused session/capture paths are rejected. A malformed request/source stops
the session visibly in `process.log`. One writer owns `requests.jsonl`; source/control operations
must be serialized. No loopback or production socket binds are performed.

## Core state mapping and limits

- MAIN_DIRECT -> FULL_6DOF.
- FALLBACK_ACTIVE with no physical FULL recovery window -> FALLBACK_IK.
- FALLBACK_ACTIVE with an active physical FULL window -> RECOVERY_DWELL.
- REACQUIRING -> RECOVERY_BLEND; completion fraction 1 -> MAIN_DIRECT.
- UNAVAILABLE remains UNAVAILABLE, never mislabeled as usable IK fallback.

Dwell resets on invalidity; ticks replaying one old sample cannot satisfy dwell. Position follows
current aligned IK during dwell, while rotation smoothly approaches selected Main rotation under
the existing rule. After dwell, position lerp and shortest quaternion interpolation run for 300ms.
Re-loss starts from the previous visible pose and returns to current corrected IK via the existing
150ms fallback blend. The pre-IK position-selection layer has a separate 300ms reacquire and no
dwell; capture distinguishes it from the post-IK visible Fusion state.

Continuous correction covers HIP-center world translation and HIP global quaternion orientation.
Valid Main remains the external output reference and continuously drives existing IK constraints.
The independent central-chain prediction uses raw HMD, raw IMU and the existing body model. Its
learned translation is retained for the 2s software hold interval, then decays under the existing
law. Rotation correction is retained only for the same IMU/calibration/assignment/world lineage.
These quantities are not per-segment offsets or absolute inertial translation. The HMD root
remains independent. This package does not activate production position correction or confer
trusted production HMD admission.

The construction-time `correctionEnabled=false` setting is the software A/B counterfactual:
both learners and Main-to-IK constraints are disabled, so the real background solver runs from
independent HMD/IMU while visible valid Main stays primary. It is not a HIL transition command.
The default and real-source template enable correction. A/B compares actual solved IK as well as
the independent prediction and corrected fallback candidate.

`rawIkPose` is the independent Main-decoupled central-chain prediction, not a second IK solver.
`correctedIkPose` is the corrected fallback candidate, `solverFallbackPosition` is the selected
pre-IK constraint, and `solvedIkPose` is the real current HumanSkeleton result. Solver convergence
error and prediction/correction residual must be assessed separately. Final output is selected
only by the existing continuity controller. Capture has no decision authority.

## Selected source for Phase 2C-2

First choice: the existing PICO Motion Tracker -> MonakaBridge calibrated MTP pose path,
connected once, using a previously usable tracker as the HIP Main. This choice reuses the existing
6DoF pipeline and avoids adding ALVR or receiver lifecycle requirements. Current physical stability
is NOT VERIFIED by 2C-1. If that one-time source is unavailable, choose another already stable
mapped 6DoF source using the same Common Pose boundary; do not fix reconnect as part of Core PoC.

Prepare an independent physical Slime IMU and an independent HMD/root pose in the same explicitly
confirmed world. The Main pose must already refer to HIP body center, and IMU orientation to HIP
body frame; this diagnostic session uses identity mounts and no automatic remount calibration.
Transform/align inputs through the existing source calibration before this boundary. Do not
invent a common-world epoch or fabricate a source generation to satisfy an assertion.

Use `input-schema.json` to forward **actual** source samples through a local JSONL/file exporter.
The source exporter owns acquisition and local-clock conversion; it must preserve native sequences,
sessions, calibration, mappings and physical sample ages. It must never substitute synthetic values
for missing real IMU/HMD input. Root/HMD and IMU older than 100ms are unavailable to correction;
Main freshness is 500ms, matching the existing runtime policy. Source disconnection is unnecessary:
the gate can mask the connected Main alone.

For a Bridge pose, use `mainMtp` containing the unchanged wire envelope and `mainReceivedAtNanos`
in the local monotonic domain instead of `main`. The package uses the hash-pinned MonakaCodec and
existing MtpPoseAdapter. It checks exact coordinate space and configured length-prefixed logical
identity; sample time is receipt minus native envelope age. The native envelope is captured.
No new codec or UDP receiver is used. Independent IMU/HMD samples must use the same local time
domain and match the actual epoch semantics (legacy null stays null). For direct Common Pose
`main`, `sourceId` is already the canonical observation ID; do not mint a second identity.

In 2C-2 an operator supplies the real-source exporter/records and confirms frames/calibration.
2C-1 prepares and tests this boundary, not real device operation or world calibration.
The file source/control surface can stream indefinitely by serialized `input` requests.

## Capture and evidence

See `capture-schema.json`, `input-schema.json`, `analyze-capture.py` and `phase2c2-report-template.md`.
Frames include full source provenance, raw input, prediction, correction, actual IK, Fusion state,
reason/timing, dwell/hysteresis/blend, final pose, residuals and per-tick continuity/velocity.
Events include validity/loss, fallback, recovery candidate, dwell start/reset, blend start/cancel,
FULL restore and correction update. `mark` annotates, without affecting Core.

Receiver checkpoint/bootstrap/accepted-metadata/Rig.live NPE/MoveFileEx AccessDenied remain
quarantined production-side defects. 2C-1/2C-2 do not certify production receiver stability.
No real HIL, body-mounted run, production endpoint, SteamVR/VRChat or backend reconnect is performed
in 2C-1. Next phase is **2C-2 — Real HIL: 6DoF FULL -> IMU/IK Fallback -> 6DoF Recovery**.
