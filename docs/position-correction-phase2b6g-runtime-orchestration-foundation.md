# Phase 2B-6G — Position Correction Runtime Orchestration Foundation

PositionCorrectionRuntimeOrchestrator binds the existing 6A–6F contracts into one
synchronous active tracking tick. It is **IMPLEMENTED / DORMANT / INJECTED**.
Explicit construction supplies all predictor, pairing, learning and reacquisition
policies. The actual ConstraintIkWriteback constructor binds projection and commit
to the same boundary. MonakaRuntime has **no production caller**.

## Immutable tick and reservation

PositionCorrectionRuntimeTick captures tickSequence, nowNanos, resolvedAtNanos,
expectedSpace, one assignment snapshot, one resolved constraint snapshot, an optional
group of Raw HMD/IMU/body/fixed calibration values, and an optional calibrated
teacher plus independently supplied current expected teacher epoch. Both maps are
copied and unmodifiable at bundle construction. No mutable registry, observation
store, pipeline, raw Tracker, body configuration or acquisition service is retained.
All source facts and normalization precede tick processing. Teacher presence is
independent of resolver Main position ownership.

Sequence must be nonnegative and strictly increasing per instance. Duplicate,
sequence rollback, negative time, time rollback, resolvedAt != now and negative
assignment generation reject before any stage or reservation. New sequences at
equal now are legal. A passed tick reserves sequence/time before stages, so an
unexpected exception cannot replay the same tick; exceptions propagate without
catch-all retry. A rejected preflight does not reserve a newer sequence.

## One-direction stage order

1. Typed predictor input assembly checks exact spaces, future raw sample times,
   fixed HMD identity and fixed body model identity before input construction.
2. Available assembly invokes the pure predictor once, using tickSequence as
   predictionSequence. Unavailable assembly invokes it zero times. Predictor
   UNAVAILABLE has no retry; its current epoch is null.
3. Teacher and available prediction invoke PositionErrorMeasurement once. Its
   pairing uses the caller's expected teacher epoch; it is never regenerated to
   match a stale sample. Other paths skip measurement.
4. Measured invokes learningLaw.observe once. All other paths invoke
   advanceWithoutMeasurement(now, currentPredictionEpoch) once. Null prediction
   context invalidates old correction. observe's reject/duplicate/outlier path
   already advances the gap internally; there is no additional advance.
5. The learning result's current state goes directly to application/continuity.
   With lineage, lastStateAdvanceAtNanos must equal now. Base HIP without position
   and available prediction invokes application at most once. Main position
   present skips application entirely. Application rejection is passed as no Ready.
6. Main position present projects its effective HIP target once using current IK
   calibration. Otherwise projection is skipped. Unavailable projection remains
   null at continuity; no endpoint is fabricated.
7. Continuity selection executes exactly once with the same bundle facts.
8. Every resolver constraint adapts through the 6F position-reference adapter;
   HIP alone is replaced with continuityResult.constraint, without another correction.
9. applySolver(finalMap, sameAssignment) executes once as the final side-effect
   commit. Its partial/rejected receipts return as diagnostics. No generic apply,
   second write, retry or stage reevaluation follows it.

Processed results retain typed assembly, prediction, measurement, learning,
application, projection, continuity, final map and writeback diagnostics, plus
branch call counts. They confer no raw-source, teacher or prediction authority for
future ticks. Solver and receipt maps are unmodifiable snapshots. Stateful learner
and continuity components are instance-owned; the test seam substitutes only the
predictor or the small synchronous solver boundary.

## Solver ownership and calibration window

The pipeline never reimplements Main/fallback/reacquisition choice. Continuity owns
HIP selection; non-HIP position/rotation values, source, quality and sample time
retain the exact resolver component objects. Position present adapts to
TRACKER_ORIGIN, absent to NONE. Fallback/reacquisition carries IK_EFFECTIVE_TARGET.

Projection, concrete continuity selection and final writeback execute in the same
owning-thread phase. No external stage callback, resetOffsets, calibration deletion,
refreshConstraintInputs, IK rebuild callback, worker or handoff intervenes. Ordinary
topology maintenance remains inside the final applySolver operation. The caller must
exclusively own this phase and prevent concurrent external calibration mutation.
No hidden clock, polling, queue or asynchronous lifecycle is added.

## Actual IK evidence and verification

PositionCorrectionRuntimeOrchestratorTests uses the real HumanSkeleton,
ConstraintIkWriteback and active IKConstraint target diagnostic with nonzero offset
and nonidentity rotationOffset. Main direct equals the projected effective target;
Main loss equals the current 6D corrected HIP_CENTER. Short loss preserves the
same-tick HOLDING correction; long loss applies only the current DECAYING correction.
Return begins at the last selected fallback anchor, then blends to current moving
Main effective target at the midpoint. Completion returns TRACKER_ORIGIN at the
same effective endpoint. Calibration and the capable HIP proxy survive these
transitions. Context loss invalidates correction and removes positional fallback.

Tests additionally cover fatal zero-side-effect paths, equal now/new tick, sequence
gaps, exception reservation, learner duplicate/outlier exclusivity, stale/skew/epoch
teacher rejection, typed assembly mismatches, application/projection/writeback
failures without retry, non-HIP preservation, immutable maps, context/assignment/
space invalidation, deterministic replay and source audits. Full Core/Desktop,
shadowJar and MTP process E2E remain the completion gate. Detailed executed counts,
numeric targets and Git receipts are in
`build/reports/phase2b6g-position-correction-runtime-orchestration-20261008/report.md`.

## Production status and next phase

Same-tick software orchestration: READY. Actual ConstraintIkWriteback via
orchestrator: VERIFIED IN TEST / DORMANT. MonakaRuntime production caller: NONE.
Production Position Correction: NOT ENABLED.

OpenVR HMD: POSE_ONLY. Strong Trusted: UNSUPPORTED. Production Raw HMD:
BLOCKED BY BACKEND. 2B-5P: NOT READY. 5S physical HIL: pending.
Raw IMU assembly, Main teacher normalization, body/fixed acquisition and calibration
persistence remain NOT CONNECTED. Raw HMD production adapter, pause/resume ownership
and enable/disable configuration are deferred. Visible OutputContinuity, Direct
output, SteamVR and admission policy are unchanged.

6G HIL: NOT REQUIRED / NOT RUN, because this phase verifies deterministic injected
facts and the actual software IK sink while production sources remain disconnected.
6G readiness does not imply production Position Correction readiness.

Next: **Phase 2B-6H — Production Integration Gate / Runtime Adapter Audit**.
Audit each source adapter and lifecycle boundary against 6G's immutable tick contract
before deciding which connections are possible. Preserve the Strong Trusted HMD
blocker; do not proceed directly to full production enablement.

## Phase 2B-6J configuration follow-up

[6J configuration/calibration foundation](position-correction-phase2b6j-configuration-calibration.md)
adds schema v3 explicit opt-in persistence and pure validated runtime-policy
materialization. v1/v2 and absent/disabled v3 remain Position Correction opt-out.
All calibration/space/policy inputs are mandatory with exact source/body/HMD/session
binding; no defaults or identity/zero inference. This supersedes earlier statements
that configuration/calibration persistence is unimplemented. Production invocation,
live source acquisition and writeback ownership remain unchanged and disconnected.
Raw HMD remains BLOCKED BY BACKEND. Next is 6K Non-HMD Production Input Adapter
Foundation; HIL for 6J is NOT REQUIRED / NOT RUN.

## Phase 2B-6K input follow-up

[6K Non-HMD input foundation](position-correction-phase2b6k-non-hmd-production-inputs.md)
captures same-tick raw IMU, calibrated Main teacher, independently owned expected
teacher context, live Body Model and configured Fixed compatibility. It never
assembles complete predictor sources or invokes the 6G orchestrator. Raw HMD
remains blocked; production caller, hard session lifecycle reset and exclusive
writeback ownership are deferred to future composition.
