# Phase 2B-6D — Position Correction Application Contract

`PositionCorrectionApplication.prepare` is an internal pure/dormant pre-IK
boundary. It accepts a pure `PositionPrediction`, immutable 6C state snapshot,
selected `EffectiveConstraint`, one assignment snapshot, explicit expected space
and application time. It returns `Ready(candidate)` or a typed `Rejected` result.
There is no clock, mutable registry, teacher object, learner instance or runtime
dependency in this API.

## Absolute world construction and ownership

```text
correctedHipCenterWorld = purePredictionHipCenterWorld + currentCorrectionWorld
```

The correction is added once, componentwise, with its existing teacher-minus-
prediction sign. No blend, gain, extra decay, normalization, clamping or incremental
accumulation is introduced. Nonfinite correction or Float addition overflow rejects.
Prediction structural eligibility is reused through the existing prediction-only
gate; HIP, HIP_CENTER, finite AVAILABLE position, provenance, four required raw/
body/fixed dependencies and absence of every forbidden dependency are mandatory.
Output namespaces in either raw-source epoch also reject.

This is a fallback-position overlay only. The base must target HIP, have no
position and have a usable rotation. A selected Main FULL position, any other
existing position, and a previously corrected constraint all reject with
`BASE_POSITION_ALREADY_PRESENT`. Main ROTATION_ONLY cannot supply another owner.

The supplied assignment must contain HIP with `useAsIkConstraint=true` and an
explicit rotation fallback. Exact source binding requires:

```text
assignment.rotationFallbackTracker.observationId
  == baseConstraint.rotation.sourceId
  == prediction.provenance.epoch.imuSourceId
```

The rotation cannot come from an output/private source. Its quaternion components
and squared length must be finite, length squared > 1e-10, and its observed time
must be in [0, application time]. No new rotation freshness threshold is added;
resolver freshness and future orchestration own that policy. Preparation and
`solverConstraint()` retain the exact original `ResolvedComponent<Quaternion>` object,
including numeric value, source, quality and time. Phase 1 Rotation Correction's
fallback numeric result can therefore coexist without recalculation. The pure
predictor continues to depend on Raw IMU, never on Rotation Correction.

## Context and currentness

The full state prediction epoch must equal the current prediction epoch, including
HMD/IMU sources, sessions, calibration, mapping, body/fixed identities, space and
assignment. Current assignment generation must match both lineage epochs.
Prediction space, prediction epoch space, state space, state prediction epoch
space, state teacher epoch space and expected space must all match exactly
(ID/convention/revision). No world-frame conversion or stale correction carryover
is performed. Teacher lineage is retained as diagnostics; no teacher position is
reread or used in application math.

```text
prediction.provenance.generatedAtNanos
  == state.lastStateAdvanceAtNanos
  == applicationAtNanos >= 0
```

Old or future prediction generation and unadvanced/null/old/future state time
reject, including a forgotten gap advance. The caller must generate and advance
at the same explicit local monotonic tick; preparation never refreshes learner
acceptance, hold age, decay clocks or state.

| State phase | Application eligibility after all other gates |
| --- | --- |
| UNINITIALIZED | Reject |
| REACQUIRING | Current bounded recovery correction |
| TRACKING | Current learned correction |
| HOLDING | Exact current held correction |
| DECAYING | Exact current decayed correction, no second decay |
| EXPIRED | Pure prediction with exact zero correction |

Missing lineage rejects in every phase. A malformed EXPIRED snapshot with nonzero
correction also rejects; phase alone never grants authority or raises quality.
Rejection precedence is time, prediction structure/currentness, state
structure/currentness, space/epoch/assignment, relation, base ownership/rotation,
then numeric application. Structural reasons are diagnostic strings attached to
the typed structural rejection; eligibility does not depend on parsing strings.

## Candidate and solver conversion

`PositionCorrectionApplicationCandidate` snapshots base position, correction and
corrected position into separate Vector3 values. It retains prediction sequence,
full provenance (physical sequences, individual times and oldest/latest support),
prediction epoch, correction phase/lineage, application time and base rotation.

`solverConstraint()` creates a full HIP `SolverEffectiveConstraint` with explicit `IK_EFFECTIVE_TARGET` and:

| Component | Metadata |
| --- | --- |
| Position value | Absolute corrected HIP_CENTER world position |
| Position source | `monaka-private:position-correction-v1:HIP` |
| Position quality | Always `DEGRADED`, including TRACKING |
| Position observed time | Prediction `inputEarliestAtNanos`, oldest physical support |
| Rotation | Exact selected base component object |

Application/generation/teacher/last-learned time never replaces physical support.
Repeated preparation of the same inputs is deterministic and idempotent. Feeding
the converted full constraint back as a base structurally rejects because its
position is already present. No mutable applied flag or sequence counter exists.

The candidate is neither `PoseObservation` nor `PositionPrediction`, and exposes
no conversion to either or to Direct OutputPose. ObservationStore and pipeline
ingest are unused. The private namespace is feedback-excluded and rejected by
TrackerReference and raw identity gates for Main teacher, HMD and IMU. Application
cannot contaminate teacher selection or feed corrected position into prediction.

## Actual solver boundary and scope

Manual tests pass the candidate to the existing `ConstraintIkWriteback` and actual
HumanSkeleton. A managed HIP raw tracker is removed from both input lists; the
capability-correct private proxy carries position and rotation and appears once
in SkeletonInputView constraints and rotations. Actual IKSolver positional
extraction accepts that usable, non-internal proxy. Repeated writeback does not
duplicate proxies or rebuild unchanged topology. Numerical IK smoke verifies a
finite changed computed HIP; this is software compatibility, not physical pose
quality. 6F adds typed effective-target precompensation and read-only calibration/target seams to writeback/IKSolver; solver iterations remain unchanged.

Application contract: **IMPLEMENTED / DORMANT**. Application runtime, predictor
runtime, temporal pairing runtime, Position Correction runtime orchestration and
its IK writeback runtime: **NOT CONNECTED**. MonakaRuntime has no caller or
knowledge of this application. MainFallbackPolicy, ConstraintResolver and
ConstraintPipeline selection are unchanged. Direct output remains disconnected.
OutputContinuityController and BackgroundIkPoseReader retain their visible output
and post-solve readback responsibilities; pre-IK correction introduces no new
visible blending or Main-return hysteresis.

OpenVR HMD: **POSE_ONLY**. Strong Trusted: **UNSUPPORTED**. Production Raw HMD:
**BLOCKED BY BACKEND**. 2B-5P: **NOT READY**. 5S physical HIL remains pending.
6D HIL: **NOT REQUIRED / NOT RUN**, because this contract is pure/dormant and
writeback compatibility uses manual software input without production Raw HMD.

The [6E solver position reacquisition contract](position-correction-phase2b6e-solver-position-reacquisition.md)
now consumes an already prepared Ready, retaining the last selected fallback
position as the Main-return anchor. Immediate fallback-to-Main position switching
would otherwise violate hysteresis convergence. 6D prepare is unchanged; its
position-present rejection remains mandatory. Reacquisition is separate from
visible OutputContinuity and has no production caller.

[6F solver effective-target semantics](position-correction-phase2b6f-solver-effective-target-semantics.md)
precompensates current IK mount calibration exclusively at writeback. Candidate
HIP_CENTER remains a world-space effective target; 6D never performs a calibration inverse.
Main calibration survives reference-mode switches on the same stable proxy.

Next: **Phase 2B-6G - Position Correction Runtime Orchestration Foundation**.
Orchestration was deferred to fix double calibration and reference-point mismatch.
Future ordering is same-phase projection -> selection -> writeback with no intervening
calibration reset/rebuild, retaining the Raw HMD blocker.

Receipts: `build/reports/phase2b6d-position-correction-application-20261008/report.md`.
