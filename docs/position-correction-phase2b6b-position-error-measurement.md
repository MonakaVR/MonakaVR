# Phase 2B-6B — Position Error Measurement Foundation

`PositionErrorMeasurement.evaluate(input, expectedTeacherEpoch, pairingPolicy)` is
an internal pure, immutable and deterministic measurement boundary. It executes
`PositionTemporalPairing.check()` internally as the sole pairability authority.
Only a real Pairable result authorizes measurement creation from that **same
PositionCorrectionInput**. No API accepts a detached/arbitrary Pairable result.
Structural eligibility alone cannot create a sample. Existing 5U preflight,
epoch, space, assignment, future, freshness and interval-distance precedence
remain unchanged; measurement implements no separate temporal checks.

## Error semantics and numerical validity

```text
errorWorld = teacherPosition - predictionPosition
predictionPosition + errorWorld ≈ teacherPosition
```

The sign is fixed to teacher minus prediction. Each component uses ordinary Float
subtraction. The vector is in `input.expectedSpace`, the exact common world-space
CoordinateSpace (ID, convention and revision). No HMD/body/HIP/IMU/root-local
conversion occurs. Target is HIP and body reference is exclusively HIP_CENTER;
TRACKER_MOUNT comparison remains structurally rejected.

Teacher and prediction positions are explicitly copied with componentwise
`Vector3(x, y, z)` construction after pairing. ktmath Vector3 itself has final
component fields and no component setters. Inputs, provenance, dependencies and
predictor output remain unmodified. The resulting data-class fields are vals;
epochs and CoordinateSpace are immutable value contracts.

Pairing preflight already validates finite sources and sample identities through
the existing value contracts. Two finite sources can still overflow subtraction
(Float.MAX_VALUE - -Float.MAX_VALUE). Any nonfinite error component returns
`ERROR_NONFINITE`, with no sample, clamp, saturation, zero substitution or partial
numeric snapshot. Zero residual is a valid Measured result. Large finite residuals
are valid, without a residual/outlier threshold. Signed zero retains ordinary
Float subtraction bits, without canonicalization.

Pairing rejection returns `PAIRING_REJECTED` plus the exact `pairingReason` and
`structuralReason`. In particular, current prediction epoch mismatch retains
`STRUCTURAL_INELIGIBLE / prediction_epoch_mismatch`. The defensive 5U
PREDICTION_EPOCH_MISMATCH reason is not made reachable by changing preflight.
Rejected contains only diagnostics, never positions, error or a sample.

## Exact sample, lineage and time contract

PositionErrorSample retains these separate facts:

| Field | Meaning |
| --- | --- |
| target, bodyReference, coordinateSpace | HIP, HIP_CENTER, exact input.expectedSpace |
| teacherPosition, predictionPosition, errorWorld | Copied numeric snapshots and world difference |
| teacherSequence | Raw Main provenance.sequence |
| predictionSequence | Prediction provenance.predictionSequence |
| teacherSampleAtNanos | Raw Main physical sample time |
| predictionInputEarliestAtNanos, predictionInputLatestAtNanos | Prediction's physical support window |
| predictionGeneratedAtNanos | Prediction generation, separate from physical support |
| evaluatedAtNanos | input.nowNanos; evaluation time, no clock reread |
| teacherToPredictionInputDistanceNanos | Exact real Pairable interval distance |
| teacherEpoch | Full Main source/upstream calibration/mapping/space/assignment/body/mount identity |
| predictionEpoch | Full HMD/IMU source/calibration/mapping, body/fixed calibration, space/assignment identity |
| assignmentGeneration | input.assignmentGeneration, matching both epochs |

There is no single synthetic physical sample time, midpoint, latest-time collapse
or generation-as-physical-time substitution. Sample sequences, timestamps,
positions and error are progression facts, separate from epoch identity. No new
dependency enum or error sequence allocator is added. Repeated evaluation of an
identical pair returns an equal sample; there is no measurement-layer dedupe.

Future learning/update clock must explicitly define which time fact it uses in a
separate phase. Teacher physical time is not implicitly correction-state time.

## Authority, feedback and dormant integration

Measured != accepted for learning. Measured != trusted bias.
Measured != correction state update. Measured != IK authority.
Pairable also remains comparison compatibility only, including for nonzero and
huge finite position differences.

This sample compares Main-decoupled prediction **with a Main teacher**; it is
Main-derived measurement, never a raw predictor dependency. It must not flow into
RawImuOrientationInput, RawHmdPoseInput, HipBodyModelSnapshot,
PredictorFixedCalibrationSnapshot or PureMainDecoupledHipPredictor. The
reconstruction equation documents sign only; prediction + error is never written
back or published.

No persistent state, last error/time, counter, offset, EMA/low-pass/Kalman, running
mean, confidence, gain, clamp, learner, correction-law interface, velocity or
acceleration correction, loss/reacquisition machine or runtime integration exists
in this boundary. Future correction-law decisions remain: measurement outlier
threshold, state update time basis, gain/tau, world versus body-local persistent
state, offset saturation, loss hold/decay, reacquisition, confidence, sample
dedupe and rollback watermarks.

Dedicated tests cover sign/reconstruction, three axes, zero, large finite errors,
overflow on all axes and signs, signed zero, exact lineage/time/sample facts,
real pairing distances, rejection propagation/precedence, old mount/body/fixed/
IMU/HMD context, space and assignment changes, sample progression, immutability
and deterministic repeat/out-of-order evaluation. A dormant chain runs actual
5Y Raw IMU production adaptation + synthetic Raw HMD + body/fixed snapshots ->
pure predictor, and raw Main PoseObservation + mount normalization -> teacher,
then real internal temporal pairing -> nonzero Measured sample. Synthetic HMD
lineage tests confer no production HMD capability.

Position Error Measurement: **IMPLEMENTED / DORMANT**. Pure predictor algorithm:
**IMPLEMENTED**. Predictor runtime, temporal pairing runtime, Position Correction
runtime and correction IK/writeback: **NOT CONNECTED**. Persistent learner and
correction law: **NOT IMPLEMENTED**. No ConstraintPipeline, Direct or SteamVR
output connection is added. OpenVR HMD: **POSE_ONLY**; Strong Trusted:
**UNSUPPORTED**; production Raw HMD: **BLOCKED BY BACKEND**; 2B-5P: **NOT READY**.
5S physical HIL remains pending. 6B HIL: **NOT REQUIRED / NOT RUN**.

Fresh regression and Git receipts:
`build/reports/phase2b6b-position-error-measurement-20261007/report.md`.

Recommended next phase: **Phase 2B-6C — Position Correction State / Bounded Learning
Law**. The immutable measurement contract now supports independent design of
stateful clock/gain/hold/decay/reacquisition semantics while runtime remains
disconnected and the HMD backend gate remains unresolved.
