# Phase 2B-5W: Calibrated Main teacher / temporal pairing integration

`PositionCorrectionInput` now directly accepts `MainHipCenterPositionTeacher`, followed
by prediction, expectedSpace, expectedPredictionEpoch, assignmentGeneration and nowNanos.
It is an internal dormant contract, consistent with the internal 5V teacher and 5U
pairing boundary. The usage audit found no production runtime constructor/caller.
Tests were migrated; no compatibility raw-PoseObservation constructor remains.
Parallel rawMainOrigin/mainBodyReference fields are removed. Raw MTP cannot bypass
explicit mount normalization by supplying a HIP_CENTER label to this comparison API.

```text
raw Main FULL PoseObservation + trusted raw origin + explicit immutable mount calibration
  -> MainTrackerMountToHipCenter.normalize
  -> MainHipCenterPositionTeacher (position-only, HIP / HIP_CENTER)
  -> PositionCorrectionInput
  -> PositionCorrectionTeacherEligibility
  -> PositionTemporalPairing.check(input, independent expectedTeacherEpoch, explicit policy)
  -> Pairable / Rejected
```

Only tests link this chain. Production MTP adapters and runtime are unchanged. The 5V
normalizer is the sole production teacher constructor. Its same-sample rotation is used
for the calibrated physical point, never carried as HIP orientation. Comparison neither
requires a rotation nor synthesizes Quaternion.IDENTITY, FULL or any PoseObservation.
It retains the very same immutable raw provenance object; no copy, clock read or restamp.

## Independent continuity

PositionTeacherEpoch contains sourceId, sourceEpoch, upstream calibrationEpoch, nullable
mappingRevision, exact coordinateSpace, assignmentGeneration, bodyReference and complete
mountCalibration (calibrationId and effective epoch). Extraction maps the teacher directly;
it proves no current context. Expected teacher epoch remains a separate required caller
argument. There is no convenience method that self-approves a sample by adopting its epoch.

The effective mount epoch is 5V's source/session/offset-content identity. Same offset
and calibration ID with a changed session invalidates; changed offset invalidates even
if session is reused. Changed calibration ID invalidates even if effective epoch content
matches. Numeric equality of HIP positions never bypasses identity mismatch. Valid old
teachers receive TEACHER_EPOCH_MISMATCH. Upstream calibration, raw source incarnation,
mapping (null->value, value->value, value->null), exact space (id/convention/revision)
and assignment changes independently reject old contexts with unchanged mount identity.

Teacher space, raw provenance space, expected space, expected teacher space, prediction
space and prediction epoch space require complete equality. Input and both expected/actual
epoch assignment generations must agree. Prediction epoch remains Main-decoupled:
no Main mount calibration is added to PositionPredictionEpoch, MainDecoupledHipInput,
FixedCalibrationIdentity, RawHmdPoseInput or RawImuOrientationInput.

## Eligibility and physical time

Structural preflight still runs first and wins with STRUCTURAL_INELIGIBLE and its
diagnostic: prediction availability/finite value/exact space/lineage, teacher usable
finite position/raw backend/source identity/feedback exclusion, HIP target/HIP_CENTER,
current prediction epoch and assignment, and nonfuture times. Nonnullable provenance
and fixed HIP_CENTER eliminate old missing-provenance/unknown-reference input states;
malformed internal fixtures with missing space fail closed without reading the throwing
teacher.space getter. TRACKER_MOUNT prediction is representable but cannot compare.

5U time semantics are unchanged: teacher physical time is provenance.sampleAtNanos;
prediction support is [inputEarliestAtNanos,inputLatestAtNanos]; generation time is separate.
Inclusive interval-distance skew, teacher age, latest-input age and generation age use
four explicit nonnegative policy limits, zero permitted. Future timestamps and Long
boundaries remain fail-closed. Publication observedAtNanos is absent from the input;
changing it before normalization cannot refresh or change pairing. Pairable retains
immutable comparison facts and both epochs, including mount lineage in teacherEpoch.

**Pairable != learning authority. Learning authority: NO.** No p_main - p_prediction,
learner, correction law, confidence/filter, decay, hysteresis or convergence is implemented.

## Runtime and next prerequisites

MTP production normalization, temporal pairing runtime, Predictor runtime, Position
Correction runtime and IK writeback remain **NOT CONNECTED**. No calibration selection
registry, acquisition automation, persistence, UI/RPC/config, protocol change, Direct
output integration or ConstraintPipeline wiring is added. OpenVR HMD remains POSE_ONLY,
Strong Trusted UNSUPPORTED, correction authority NO, and 2B-5P NOT READY. 5S HIL stays
separately pending; this dormant integration requires no HIL (NOT REQUIRED / NOT RUN).

Main teacher physical point, raw provenance, mount lineage and temporal comparison are
software-ready under explicit supplied context. Future runtime must derive independent
expected teacher epoch from **current assignment, current trusted raw source context and
current mount calibration selection**; cached sample identity cannot establish currency.

[Phase 2B-5X](position-correction-phase2b5x-body-model-predictor-handoff.md) now passes immutable HipBodyModelSnapshot geometry and complete model ID/content epoch into the dormant predictor contract. Body changes reject old predictions through existing structural precedence at equal numeric positions; teacher lineage and mount calibration remain independent. The recommended next phase is Raw IMU Production Boundary: a production adapter with exact space and raw evidence is still absent. Fixed Calibration numerical semantics, predictor algorithm and trusted HMD runtime gates remain deferred.

Software gates: CalibratedMainTeacherTemporalPairingTests, 5V mount tests, the migrated
45-case 5U temporal suite, 13-case prediction suite, full Core/Desktop, shadowJar and
all 11 MTP process E2E stages. Detailed fresh results and preserved source evidence are
in build/reports/phase2b5w-calibrated-main-teacher-integration-20261007/report.md.
