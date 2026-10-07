# Phase 2B-6C — Bounded Position Correction Learning Law

`PositionCorrectionLearningLaw` is an internal dormant numerical state machine.
Its only comparison input is the immutable 6B `PositionErrorSample`. The other
entry point is `advanceWithoutMeasurement(nowNanos, currentPredictionEpoch)`;
Main availability detection and input assembly belong to future orchestration.
There is no internal clock, runtime object, correction application or IK writeback.

## Physical identity prerequisite

6A `predictionSequence` remains caller-owned predictor invocation progression.
It is neither physical identity nor an epoch, and changing it cannot authorize
learning. `PositionPredictionProvenance` now requires exact raw
`inputHmdSequence`, `inputHmdSampleAtNanos`, `inputImuSequence` and
`inputImuSampleAtNanos`. All are nonnegative, and the constructor requires
support earliest/latest to equal the min/max of the individual sample times.
The pure predictor copies all four directly from its raw inputs; 6B copies them
as `predictionHmdSequence/SampleAtNanos` and `predictionImuSequence/SampleAtNanos`.
Generation, evaluation, teacher physical time and support remain distinct facts.

## State and lineage

`correctionWorld` is a world-space translation in the exact CoordinateSpace
(ID, convention, revision). Its sign follows `errorWorld = teacher - prediction`:
`prediction + correctionWorld` would approach the teacher. That expression is
not applied to any output. Full `PositionCorrectionLearningLineage` binds the
entire teacher epoch and prediction epoch using existing value equality.

Teacher source/session/upstream calibration/mapping/mount/body-reference/space/
assignment changes, and prediction HMD/IMU source/session/calibration/mapping,
body model, fixed calibration, space or assignment changes immediately discard
old correction, watermarks, recovery counters and clocks. No transform, decay or
numeric carryover occurs. A new valid measurement seeds the new lineage from
zero. A gap with changed or null prediction context invalidates to UNINITIALIZED
and clears lineage. During compatible teacher loss the last teacher lineage is
retained; the returning teacher is checked against it. Context invalidation takes
precedence over an old state clock, since even a stale context-change event cannot
justify retaining incompatible world numbers.

Immutable snapshots contain phase, Float Vector3 correction, space, full lineage,
separate observed/accepted physical watermarks (including all individual times),
last accepted invocation sequence for diagnostics, state advance time and recovery
streak/start. They are numerical/diagnostic state, **never application authority**.

## Explicit policy and clocks

Every `PositionCorrectionTuning` field is mandatory, with no production defaults:

| Field | Validation |
| --- | --- |
| trackingTauSeconds, recoveryTauSeconds, decayTauSeconds | finite > 0 |
| maxResidualMeters | finite > 0 |
| maxCorrectionMagnitudeMeters, maxCorrectionRateMetersPerSecond, maxUpdateStepMeters | finite > 0 |
| maxLearningDtNanos | > 0 |
| holdNanos | >= 0 |
| maxDecayRateMetersPerSecond | finite > 0 |
| recoveryResidualMeters | finite, 0..maxResidualMeters |
| recoveryStableNanos | >= 0 |
| recoverySamples | > 0 |
| zeroEpsilonMeters | finite, 0..maxCorrectionMagnitudeMeters |

Synthetic policy values in tests are not HIL recommendations. Measurement learning
`dt = current.teacherSampleAtNanos - lastAccepted.teacherSampleAtNanos` uses teacher
physical time exclusively. Generation and evaluation never replace it. Same-lineage
state advance uses evaluatedAt or explicit now in the same local monotonic domain;
negative or rollback state times reject. Nonnegative ordered subtraction is safe
through Long.MAX_VALUE.

## Progression and rejection

Teacher sequence must strictly advance and its physical time must strictly
advance. Neither raw sequence may roll back, and at least one must advance.
A repeated source sequence with changed sample time is an identity mismatch;
advancing raw sequences with decreasing physical time also reject. Equal raw
times with advancing raw sequences are permitted. Caller invocation sequence has
no progression role. A repeated teacher or unchanged raw pair cannot relearn.

Identity-consistent non-rollback observations update observed watermarks before
residual acceptance. Outliers thus cannot be replayed with a new invocation or
changed value to earn another attempt. Accepted watermarks change only at seed
or successful learning. Rejections and duplicates never refresh acceptance,
learning dt or hold age. They reset the recovery streak and evolve compatible
gap state through evaluatedAt. Rollbacks/mismatches preserve observed watermarks.
Typed decisions/reasons distinguish seed/update, duplicate/rejection, hold/decay/
expiration and hard invalidation; each includes an immutable snapshot.

The first unique non-outlier sample seeds accepted clocks and recovery evidence,
with correction unchanged (zero in a new lineage), phase REACQUIRING and no jump.
It never directly becomes TRACKING, even with a one-sample/zero-window policy.
`dt > maxLearningDtNanos` reseeds recovery without a numeric update; equality is
allowed. Residual outliers remain valid 6B measurements but are rejected by 6C.

## Bounded learning and recovery

```text
r = errorWorld - correctionWorld
reject if |r| > maxResidualMeters
alpha = 1 - exp(-dtSeconds / tau)  (computed as -expm1 for small dt)
step = r * alpha
step norm limit = min(maxUpdateStepMeters, maxCorrectionRateMetersPerSecond * dtSeconds)
candidate = correctionWorld + direction-preserving bounded step
correctionWorld = direction-preserving sphere bound(candidate, maxCorrectionMagnitudeMeters)
```

Double vector arithmetic and max-component-scaled norms avoid Float subtraction/
square overflow. Nonfinite residual/norm/alpha/rate product/step/candidate rejects
without putting NaN or Inf in state. Snapshots use directed Float conversion
toward zero so rounding cannot expand the correction sphere. No component clamp
or measurement clamp is used. Internal accumulation retains Double precision.

REACQUIRING uses recoveryTauSeconds; TRACKING uses trackingTauSeconds. Compatible
short HOLDING from stable tracking resumes tracking tau. DECAYING/EXPIRED always
return through recovery tau. A physical teacher gap exceeding hold also resets
recovery when no explicit gap tick arrived. Post-update remaining residual must
be <= recoveryResidualMeters. Consecutive good samples must reach recoverySamples
and teacher physical time since the good streak started must reach
recoveryStableNanos, both inclusive. Bad residual, outlier, rollback, duplicate,
large-dt reseed or decay resets the good streak. TRACKING conveys no IK authority.

## Hold, decay and phases

| Phase | Meaning |
| --- | --- |
| UNINITIALIZED | No accepted sample; correction zero |
| REACQUIRING | Bound learning with incomplete stable recovery gate |
| TRACKING | Internal residual/count/time gate satisfied |
| HOLDING | Compatible loss, last accepted teacher age <= holdNanos |
| DECAYING | Compatible loss beyond hold; bounded decay toward zero |
| EXPIRED | Decayed magnitude <= epsilon; exact zero |

Hold age is `now - lastAccepted.teacherSampleAtNanos`, never evaluation age.
The inclusive boundary holds exactly; hold=0 is legal, with decay eligibility
1ns after the teacher time. Decay starts at the logical hold boundary, using only
time after `max(lastStateAdvanceAt, teacherAt + hold)`. Addition is performed only
when age>hold proves it cannot overflow. Exponential reduction toward zero uses
decayTauSeconds and is limited by maxDecayRateMetersPerSecond * decayDtSeconds.
Direction is preserved; magnitude cannot increase or overshoot. Pure exponential
decay is independent of tick partition. At <= zeroEpsilonMeters the state snaps
to exact zero/EXPIRED. Returning samples recover from retained bounded state or
zero without snapping to error. UNINITIALIZED gaps do not invent DECAYING state.

## Verification, authority and remaining work

Dedicated tests cover physical provenance, observed/accepted clocks, duplicates,
identity mismatches/rollback, bounds, extreme finite and nonfinite vectors, tau,
physical learning time, inclusive limits, Long boundaries, hold/decay/recovery,
all epoch fields, immutable snapshots and deterministic replay. An actual 5Y
physical IMU fixture plus synthetic Raw HMD, body/fixed snapshots, pure predictor,
raw Main plus mount normalization and real 6B measurement runs first seed and
second bounded update. A deterministic sequence exercises
TRACKING -> HOLDING -> DECAYING -> REACQUIRING -> TRACKING. Existing rotation,
core, desktop, shadowJar and process MTP E2E remain regression gates.

Pure predictor algorithm: **IMPLEMENTED**. Position Error Measurement:
**IMPLEMENTED / DORMANT**. Bounded learning law: **IMPLEMENTED / DORMANT**.
Runtime input assembly, predictor runtime, temporal pairing runtime, runtime
learner, Position Correction runtime and IK writeback: **NOT CONNECTED**.
The [6D application contract](position-correction-phase2b6d-application-contract.md)
now defines a separately validated pure/dormant solver-facing candidate. The 6C
state alone still grants no application authority. Production IK/output correction
application remains **NOT CONNECTED**. No ConstraintPipeline, Direct or SteamVR
authority is added.

OpenVR HMD: **POSE_ONLY**. Strong Trusted: **UNSUPPORTED**. Production Raw HMD:
**BLOCKED BY BACKEND**. 2B-5P: **NOT READY**. 5S physical HIL: pending.
6C HIL: **NOT REQUIRED / NOT RUN** (dormant numerical machine, explicit synthetic
policy and no runtime application).

Receipts: `build/reports/phase2b6c-bounded-position-correction-learning-20261007/report.md`.
6D supplies the pure/dormant exactly-once pre-IK/IK boundary, including rotation
preservation and structural double-application rejection. The
[6E solver reacquisition contract](position-correction-phase2b6e-solver-position-reacquisition.md)
now closes Main-return position continuity using current snapshots only, without
learning changes. Next: **Phase 2B-6F —
Position Correction Runtime Orchestration Foundation**, with injected software
inputs and the production Raw HMD blocker retained.
