# Phase 2B-6J — Position Correction Configuration / Calibration Foundation

MonakaConfiguration is the persistent source of truth for explicit Position
Correction space, calibration and policy inputs. It saves schema **v3** and loads
v1/v2/v3. This phase establishes immutable configuration values only. Production
Position Correction is **NOT ENABLED**; MonakaRuntime and MonakaServerIntegration
have no new orchestrator caller, source acquisition or writeback ownership.

## Opt-in and migration

`positionCorrection: PositionCorrectionConfig? = null` is public. Absent v3 section
and `{ "enabled": false }` load as null. Saving null omits the section. A disabled
section must contain only `enabled`; stale calibration fields are rejected.
Explicit JSON null is rejected. Enabled sections require every field, including
all nested fields, with no unknown keys. Typos, nulls, wrong shapes and missing
values fail closed. No identity quaternion, zero offset, source ID, calibration ID,
session, policy or tuning is inferred. Explicit zero/identity calibration remains
legal when the existing factory accepts it.

v1/v2 always load Position Correction disabled. A Position Correction section in
an older version is rejected, even disabled/null. v1 still needs explicit legacy
publisher/source/tracker mappings. v2 assignments, output modes, background IK
alignment, visible continuity and Rotation Correction retain their semantics.
All saves use v3. `migrate()` validates the entire document before any write,
copies original bytes to `.pre-c2.bak`, then atomically saves current v3 through
`.pending`. The historical backup suffix is retained. Existing backups cannot be
overwritten; invalid mappings or enabled sections leave the file and backup intact.

## Public DTOs and internal materialization

The public DTO family is `PositionCorrectionConfig`, RawImuSpaceConfig,
MainMountCalibrationConfig, FixedCalibrationConfig, PredictorPolicyConfig,
PairingPolicyConfig, LearningTuningConfig and ReacquisitionTuningConfig (each nested
type has the `PositionCorrection` prefix). Every constructor parameter is explicit.
Nested constructors validate immediately against existing factories/runtime policy
constructors. MonakaConfiguration additionally validates assignment/space binding
on construction/load and again against one captured assignment map before save,
so later reassignment cannot persist a stale source confirmation or mount.

`PositionCorrectionConfig.toFoundation()` returns internal
`ConfiguredPositionCorrectionFoundation`: raw IMU binding, Main mount snapshot,
Fixed snapshot and predictor/pairing/learning/reacquisition runtime policies.
This conversion is pure and total for a valid DTO. It needs no Tracker,
PoseObservation, HumanSkeleton, SkeletonConfigManager, MonakaRuntime, clock,
assignment snapshot, resolved tick or live HMD/IMU/Main/body-model acquisition.
Unavailable calibration factory results are configuration invariant failures.
Existing internal contracts retain their visibility. No learner/process, admission,
live adapter or solver/writeback is invoked by persistence/materialization.

Vector3 and Quaternion are immutable ktmath value types. DTOs retain these values;
nonmutating arithmetic/unit operations cannot change a retained config or snapshot.
There is no hot reload or automatic live mutation. No IDs are generated at load.

## Exact source and calibration binding

HIP must be assigned, participate with `useAsIkConstraint == true`, and have an
explicit physical Slime fallback (`mtp == null`, `slime:` observation namespace).
No restriction is added on IK/Direct/Hybrid output mode; visible output semantics
belong to later integration. The current teacher config requires MTP Main: the
existing production raw teacher path is MTP and no additional backend authority
is established here. Config cannot certify that a configured Slime source is a
physical device; the dormant production boundary remains responsible for checking
live physical/IMU facts.

Raw IMU `confirmed` must be true, its source must exactly equal the assigned HIP
rotationFallbackTracker.observationId, and its CoordinateSpace must equal config
space in **id, revision and convention**. This is source-specific operator
confirmation, independent of Rotation Correction's frame/shared-space assertion.
It materializes the exact source, space and confirmation into internal
SlimeRawImuCoordinateSpaceBinding. Changing fallback requires explicit new config.

Main mount stores calibrationId, sessionEpoch, sourceId and finite XYZ metres
from tracker origin to HIP_CENTER in tracker-local axes. sourceId must exactly
equal HIP mainTracker.observationId. The sole epoch/identity authority is
`MainTrackerMountCalibrationSnapshot.create()`. Translation signed zero is
preserved. Main session changes on physical remount/recalibration, not per sample.

Fixed calibration stores calibrationId, sessionEpoch, hmdSourceId, bodyModelId,
finite HMD-local XYZ metres to HEAD anchor, and finite nonzero orientation WXYZ.
Config WXYZ is distinct from MTP wire XYZW. The sole identity, normalization and
canonical-sign authority is `PredictorFixedCalibrationSnapshot.create()`; parser
and DTO contain no copied epoch algorithm. Persistence retains the explicit input
orientation; materialization yields the factory's canonical orientation. Both
save/load/materialize identity and q/-q canonical semantics are deterministic.
Fixed session identifies HMD/head fit recalibration or a loaded calibration session,
not an update counter. IDs are stable opaque nonblank strings.

bodyModelId, hmdSourceId and sessionEpoch are persisted exactly, with no live
rebinding helper. A future adapter seeing a different live bodyModelId must mark
fixed calibration unavailable / recalibration required; it must also enforce live
HMD source binding. Storing hmdSourceId neither makes that source trusted nor
provides Strong Trusted backend evidence.

## Explicit policies

| Object | Mandatory fields | Existing validation authority |
|---|---|---|
| predictorPolicy | maxInputSkewNanos, maxHmdSampleAgeNanos, maxImuSampleAgeNanos | MainDecoupledHipPredictorPolicy; nonnegative, zero means exact-only |
| pairingPolicy | maxTeacherToPredictionInputSkewNanos, maxTeacherAgeNanos, maxPredictionInputAgeNanos, maxPredictionGenerationAgeNanos | PositionTemporalPairingPolicy; nonnegative, zero means exact-only |
| learningTuning | trackingTauSeconds, recoveryTauSeconds, maxResidualMeters, maxCorrectionMagnitudeMeters, maxCorrectionRateMetersPerSecond, maxUpdateStepMeters, maxLearningDtNanos, holdNanos, decayTauSeconds, maxDecayRateMetersPerSecond, recoveryResidualMeters, recoveryStableNanos, recoverySamples, zeroEpsilonMeters | PositionCorrectionTuning; all original positivity and cross-field bounds |
| reacquisitionTuning | reacquireDurationNanos | PositionCorrectionReacquisitionTuning; strictly positive |

Nanos/counts are representable JSON integers (Long/Int), never strings or
floating point. Tuning uses finite Double numbers. Geometry uses fixed-length
finite arrays representable in Float, rejecting overflow and nonzero underflow
to zero. Long.MAX_VALUE policies roundtrip without floating-point conversion.
Pre-IK reacquisition nanoseconds are independent of visible
ContinuityTuning.reacquireDurationMs; no continuity default is reused here.

Values are **explicit operator/config inputs, not HIL tuned defaults**. The
following synthetic fixture illustrates valid structure, not production guidance.
The assignment must bind MTP `(publisher, source, tracker)` and Slime `hip-imu`
with HIP IK participation. Parent space must match the shown world exactly.

```json
"positionCorrection": {
  "enabled": true,
  "rawImuSpace": {
    "sourceId": "slime:hip-imu", "confirmed": true,
    "space": {"id": "config-world", "revision": 7, "convention": "rh_y_up_neg_z_forward"}
  },
  "mainMountCalibration": {
    "calibrationId": "main-fit", "sessionEpoch": "mount-session",
    "sourceId": "mtp:9:publisher6:source7:tracker",
    "trackerToHipCenterLocalOffsetMeters": [0.0, -0.08, 0.02]
  },
  "predictorFixedCalibration": {
    "calibrationId": "head-fit", "sessionEpoch": "head-session",
    "hmdSourceId": "hmd:configured", "bodyModelId": "model:exact",
    "hmdToHeadAnchorLocalOffsetMeters": [0.01, -0.1, 0.0],
    "hmdToHeadAnchorOrientationWxyz": [1.0, 0.0, 0.0, 0.0]
  },
  "predictorPolicy": {
    "maxInputSkewNanos": 0, "maxHmdSampleAgeNanos": 123, "maxImuSampleAgeNanos": 456
  },
  "pairingPolicy": {
    "maxTeacherToPredictionInputSkewNanos": 0, "maxTeacherAgeNanos": 456,
    "maxPredictionInputAgeNanos": 789, "maxPredictionGenerationAgeNanos": 1234
  },
  "learningTuning": {
    "trackingTauSeconds": 0.1, "recoveryTauSeconds": 0.2,
    "maxResidualMeters": 1.0, "maxCorrectionMagnitudeMeters": 2.0,
    "maxCorrectionRateMetersPerSecond": 3.0, "maxUpdateStepMeters": 0.05,
    "maxLearningDtNanos": 123456789, "holdNanos": 87654321,
    "decayTauSeconds": 0.3, "maxDecayRateMetersPerSecond": 0.4,
    "recoveryResidualMeters": 0.01, "recoveryStableNanos": 34567890,
    "recoverySamples": 3, "zeroEpsilonMeters": 0.00001
  },
  "reacquisitionTuning": {"reacquireDurationNanos": 987654321}
}
```

DTO property `fixedCalibration` is serialized as `predictorFixedCalibration` to
identify the predictor relation distinctly from the Main teacher mount.

## Coexistence, validation and production status

Rotation Correction remains independently opt-in with unchanged schema and
constructor semantics. Both sections can coexist on compatible HIP Hybrid
assignments; its own explicit backgroundIkSharedSpace/frames assertions remain
required. Position Correction never borrows its confirmations or tuning.

PositionCorrectionConfigurationTests cover every field missing/null, unknown keys
at every object, malformed shapes/types, negative/fractional/string/overflow
integers, tuning ranges, nonfinite/Float overflow/underflow geometry, wrong-length
arrays, exact source/space binding, all output modes, repeated calibration identity
roundtrip with signed zero, factory canonicalization, body/HMD/session lineage,
immutability and fail-before-write migration/backup behavior. Existing Rotation
Correction and full runtime/solver/backend/output tests remain regression gates.
Actual executed test counts and Git receipts are in
`build/reports/phase2b6j-position-correction-configuration-20261008/report.md`.

Config: IMPLEMENTED / PERSISTED / OPT-IN. Calibration records: IMPLEMENTED /
PERSISTED. Runtime policies: MATERIALIZABLE. Production Position Correction:
NOT ENABLED. MonakaRuntime orchestrator caller: NONE. Raw IMU, Main teacher and
Body Model live adapters: NOT CONNECTED. Writeback: existing generic owner.
OpenVR HMD: POSE_ONLY. Strong Trusted: UNSUPPORTED. Production Raw HMD:
BLOCKED BY BACKEND. 2B-5P: NOT READY. 5S physical HIL: pending.
HIL: NOT REQUIRED / NOT RUN (config/calibration persistence only; no live source
integration or tracking activation).

Next: **Phase 2B-6K — Non-HMD Production Input Adapter Foundation**. Combine 6I's
pinned tick snapshot with 6J's validated config for immutable same-tick Raw IMU,
raw MTP Main -> calibrated teacher, Body Model and Fixed calibration capture.
Raw HMD remains blocked; orchestrator invocation, writeback handoff and production
Position Correction enablement remain outside that next adapter foundation.

2026-10-08 software validation: Position config 284 PASS, Rotation config 4 PASS,
full Core 1672 PASS, full Desktop 131 PASS, shadowJar PASS, MTP E2E 11 stages PASS.
Both full suites have zero failures, errors and skipped tests.

## Phase 2B-6K input follow-up

[6K Non-HMD input foundation](position-correction-phase2b6k-non-hmd-production-inputs.md)
consumes the configured foundation without global rereads or automatic rebinding.
Current Main/fallback sources must match exactly; Fixed bodyModelId must match the
single live atomic publication. Backend-owned MTP current context supplies the
independent expected teacher epoch. Non-HMD capture is READY / DORMANT; complete
predictor sources, production orchestration and writeback remain unavailable.
