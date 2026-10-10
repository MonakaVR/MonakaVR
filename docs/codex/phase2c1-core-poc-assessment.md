# Phase 2C-1 read-only assessment (before source changes)

Instruction: `C:/Users/nynyp/Downloads/Phase_2C-1_Core_6DoF_IMU-IK_Fallback_PoC_Readiness_HIL_Harness.md`, read in full. The human explicitly adopts it as this phase's instructions. It supersedes the earlier R3 priority; no receiver work is planned.

Audited Core base: `feature/trusted-hmd-common-world-receiver-v2.1`, `299dda9d31d98ac28da018c5be2a916e36193566`. Clean worktree and remote branch agree. This is the Core 1,875 / Desktop 153 lineage used by the previous regression. Root checkout is a different diagnostic lineage (`0687d6a388bba45dc8a1511c3f8d378793655076`) with two untracked launchers. It is retained untouched. No Core changes are present at the time of this assessment.

| Intended behavior | Current implementation at audited base | Missing behavior | Current tests | HIL observability gap |
|---|---|---|---|---|
| Common Pose / explicit Main owns both components while FULL | PoseObservation -> ConstraintPipeline -> ConstraintResolver, explicit TrackerBodyAssignments; MtpPoseAdapter uses pinned codec | HIL availability mask before resolver; no receiver dependency needed | Runtime, resolver, MTP, assignment, freshness tests | Raw input plus masked eligibility must be recorded separately |
| Independent IMU continues through loss | Slime raw orientation capture, provenance/freshness/epoch checks; rotation fallback explicitly assigned | Standalone local source/replay carrier | SlimeIndependentImuOrientationCapture, SlimeRawImuProductionBoundary, AssignedImuSampleFreshness | Per-frame raw IMU and raw IK required |
| Existing IK continuously follows FULL constraints | Desktop commits generic constraints before HumanPoseManager.update; live HumanSkeleton/IKSolver remains enabled and calibrated | No replacement solver needed | HybridFoundation, writeback, numerical IK tests | Need actual solver pose plus independent baseline prediction |
| Continuous orientation correction | HipRotationCorrection learns world quaternion correction from physical pairs during FULL; applies only to assigned IMU fallback | Harness integration and drift A/B proof in complete path | HipRotationCorrection, HybridServerIntegration, raw IMU boundary | Correction quaternion, readiness, age, angular residual |
| Continuous position correction retained on loss | PureMainDecoupledHipPredictor + PositionErrorMeasurement + PositionCorrectionLearningLaw + application + runtime orchestrator exist; learner holds/decays; source/epoch fences | Normal Desktop commit remains generic; production gate is CONFIGURED_RAW_HMD_BLOCKED. Explicit local HIL assembly can reuse the existing orchestrator and real writeback without claiming production ingress trust | PositionCorrectionRuntimeOrchestrator, learning/application/continuity, MonakaSolverComposition | Translation offset/state, raw prediction vs corrected candidate vs actual solved pose |
| FULL -> fallback -> dwell -> blend -> FULL | OutputContinuityController MAIN_DIRECT / FALLBACK_ACTIVE / REACQUIRING; dwell uses advancing physical FULL sample times, resets on loss; 150ms dwell / 300ms reacquire / 150ms loss blend provisional defaults | Dwell diagnostic state is folded into FALLBACK_ACTIVE. Expose read-only semantic mapping and dwell progress, preserving state names | OutputContinuity, HybridFoundation and desktop integration | Explicit RECOVERY_DWELL semantic state, physical dwell elapsed and threshold; event timestamps |
| Pre-IK recovery position continuity | PositionCorrectionSolverContinuity retains selected fallback anchor and blends solver targets; explicit caller duration | No dwell in this pre-IK layer. Post-IK output continuity supplies the dwell/hysteresis contract; do not conflate the two | PositionCorrectionSolverContinuity | Capture both solver selection phase and visible Fusion state |
| Smooth final pose | DirectConstraintOutput after post-IK continuity; private outputs excluded from raw inputs | Local capture sink in place of SteamVR/production receiver | DirectConstraintOutput, feedback tests | Full numerical frames, event stream, velocity/discontinuity/residual metrics |
| Replay and bounded local control | Extensive deterministic unit fixtures and semantic diagnostics; HybridTrackingDiagnosticRecorder only compact semantic changes | Standalone stdin/control-file process, deterministic scenarios, JSONL capture and artifact | Existing deterministic clocks/fixtures; no standalone full PoC package | Required frame/event schema, status/mark/start/stop/shutdown |

## Correction scope and limits

Reuse existing HIP-center world translation learner and HIP world quaternion correction. Existing IK receives live position/rotation constraints and solves its unchanged chains. HMD/root pose remains an independent input. This phase does not add per-segment drift calibration, inertial translation integration, automatic world alignment, backend ownership changes, or Direct-quality whole-body correction. Calibration/world assertion is explicit for a local diagnostic session; a fixture value never proves trusted production HMD ingress.

## Implementation boundary

Create an explicit standalone HIL owner, with one clock, one assignment, one ConstraintPipeline/resolver, one existing correction orchestrator, one real ConstraintIkWriteback/HumanPoseManager/IKSolver, and one existing OutputContinuityController. Gate alters only Main quality/modality eligibility before normal selection and correction teacher use. It retains raw values and all provenance. Never write final pose from control commands. Expose controller diagnostics read-only; schemas/capture do not drive decisions. Initial gate mode is inactive. No receiver or Desktop production activation changes.

## Quarantine and protection

Receiver checkpoint/bootstrap/accepted-metadata/Rig.live NPE/MoveFileEx AccessDenied remain separate production-side defects, referenced by `../phase2b5air3-rig-live-stability-20261010/report.md`. Receiver suites are not a Core PoC gate; no receiver code is changed. 2C-1 / 2C-2 do not certify receiver stability.

Preserve frozen 5S 4,285 hashes / 8,376 inventory in the direct-6dof worktree, installed driver, root config, prior evidence and unrelated worktrees. Changes go in a new phase worktree. Real HIL and backend/device/OS actions are NOT RUN in 2C-1.

## Implemented result

The HIL package reuses the components above without production receiver activation. A production-relevant Core continuity edge was minimized: after four physical recovery-dwell frames, re-loss produced a 0.10954752564430237m step. Native FALLBACK_ACTIVE covered both fallback and dwell, so cancellation did not renew the visible fallback anchor. The minimal fix recognizes an active dwell window as a new loss edge and uses the existing fallback blend from the last output. The targeted case and standalone dwell/blend re-loss now produce zero position step on the edge. No receiver code changed.

Software gates: Core 1,875 -> 1,910 (35 added, every declaration discovered, no failures/errors/skips), Desktop 153, Bridge 53/53, publisher identity 28/348, trusted producer 48/501 plus adapter 13 / host 21 and distributed process 22. Standalone process acceptance covers seven scenarios, an independent identical-capture replay, an inactive-gate run and an A/B drift run. The A/B counterfactual disables both learners and Main-to-IK constraints while retaining valid Main as the visible reference; the corrected path proves reduced actual solver residual and corrected fallback entry.

The initial test-discovery count was 32/35 because three expression-bodied tests returned exceptions. They were corrected to return Unit and all 35 were executed. The initial count discrepancy and the pre-fix dwell failure are retained in external evidence.

Final hash-fixed artifact, measured metrics, commit/push identity and evidence manifest are recorded after commit in `C:/Users/nynyp/Downloads/6Dof/hil/phase2c1-core-poc-hil-harness-20261010/report.md`. The package documentation and 2C-2 template define source/exporter/calibration preparation; physical source stability and real HIL remain NOT RUN.
