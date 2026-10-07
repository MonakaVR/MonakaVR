# Phase 2B-5U: Position temporal pairing boundary

`PositionTemporalPairing.check(input, expectedTeacherEpoch, policy)` adds an internal,
stateless production boundary after `PositionCorrectionTeacherEligibility`.
`eligible_for_pairing` remains structural preflight. `Pairable` means the sample and
prediction are comparison candidates in time and current context. **Pairable does not
mean learning is allowed.** Phase 2B-6B adds a dormant production measurement
caller; there is still no production runtime caller.

## Time and explicit policy

All timestamps are already in Monaka's local nonnegative monotonic domain. The boundary
does not read a clock, map a remote timestamp, cache a sample or mutate state.

| Meaning | Field |
| --- | --- |
| Teacher physical sample T | `input.mainTeacher.provenance.sampleAtNanos` |
| Prediction physical support [E,L] | `prediction.provenance.inputEarliestAtNanos`, `inputLatestAtNanos` |
| Prediction generation G | `prediction.provenance.generatedAtNanos` |
| Now N | `input.nowNanos` |

The position-only comparison input has no `observedAtNanos` or raw PoseObservation field.
Publication/read time never substitutes for teacher physical time. Generation is not
input freshness. Neither sequence is a time.

`PositionTemporalPairingPolicy` requires four explicit nonnegative nanosecond limits,
with no production defaults. Inclusive conditions are:

- `N - T <= maxTeacherAgeNanos`.
- `N - L <= maxPredictionInputAgeNanos`.
- `N - G <= maxPredictionGenerationAgeNanos`.
- `distance(T, [E,L]) <= maxTeacherToPredictionInputSkewNanos`.

Distance is zero inside the inclusive window, `E - T` before it, and `T - L` after it.
Equality passes; limit plus one rejects. Zero age requires its timestamp to equal now;
zero skew requires T inside the window. The earliest input is not the freshness criterion.

Future teacher, earliest input, latest input and generation fail closed. Constructors
guarantee nonnegative T and `0 <= E <= L <= G`; preflight rejects negative N and future
T/G. Explicit future guards remain in the temporal boundary. Subtraction occurs only
after `0 <= timestamp <= N <= Long.MAX_VALUE` is established. Ages and distances are
representable even across the full range, without overflow or saturation.

## Teacher and prediction continuity

`PositionTeacherEpoch` holds Main source ID, source/reconnect epoch, calibration/input
session epoch, nullable mapping revision, exact space, assignment generation, fixed
HIP_CENTER body reference and complete Main mount calibration identity (calibrationId
and effective session/content epoch, added by 5W). Upstream calibration and physical
mount calibration remain independent. `PositionTeacherEpoch.from(input)` extracts the
position-only teacher fields, returning null for missing space, blank source ID, invalid
assignment generation or origin/source ID disagreement. Extraction alone proves neither
eligibility nor current context. Provenance and body reference are now guaranteed by type.

The caller must supply a separately established **current expected teacher epoch** from
current assignment/backend context and current mount calibration selection. Extracting an old sample's epoch and reusing it as
expected does not establish continuity. Complete equality is required; null-to-value,
value-to-value and value-to-null mapping changes all alter identity. Sequence, sample
time, observed time and numeric pose are progression, excluded from epoch identity.

Teacher identity is never copied into `PositionPredictionEpoch`. Prediction lineage
continues to include HMD and IMU source IDs, source/calibration epochs and nullable
mappings, body-model epoch, fixed-calibration epoch, exact space and assignment
generation. It must completely equal `input.expectedPredictionEpoch`. Numeric equality
never bypasses either epoch mismatch.

Exact space equality includes ID, convention and revision. Main provenance, expected
space, prediction space, prediction epoch space and expected teacher space must agree.
Input, expected prediction epoch, actual prediction epoch and expected teacher epoch
assignment generations must agree. Structural preflight still requires HIP_CENTER /
HIP_CENTER; the Main comparison type has no TRACKER_MOUNT path. A TRACKER_MOUNT
prediction fails with body_reference_mismatch. No transform is performed in pairing.

## Deterministic rejection precedence

The structural preflight runs first. Every failure returns typed
`STRUCTURAL_INELIGIBLE` with the original diagnostic in `Rejected.structuralReason`.
Temporal checks never overwrite that failure. Existing prediction epoch mismatch yields
`STRUCTURAL_INELIGIBLE / prediction_epoch_mismatch`; future teacher or generation yields
`STRUCTURAL_INELIGIBLE / future_sample`. A future input necessarily implies future
generation under constructor ordering, so it has the same structural result. Tests
explicitly verify these results, including each future timestamp and epoch component.

After preflight, checks run in order: teacher epoch extraction/equality, prediction
epoch equality, exact space, assignment, future timestamps, teacher age, latest input
age, generation age, interval distance. Defensive typed context/future reasons remain
in the implementation, although current preflight already rejects those conditions.
The preflight is not changed to make these defensive branches observable. Valid teachers
from an old mount identity return TEACHER_EPOCH_MISMATCH, even with equal numeric positions.

`Pairable` retains immutable teacher/support timestamps, interval distance and distinct
teacher/prediction epochs. It carries no error vector, learning weight, correction
amount, authority, mutable solver/tracker reference, history or offset.

## Runtime and deferred prerequisites

Learner, Predictor runtime and Position Correction runtime remain **NOT CONNECTED**.
Predictor algorithm and correction law remain **NOT IMPLEMENTED**. No
`p_main - p_prediction` calculation, prediction publication, ConstraintPipeline
registration, IK writeback or Direct/visible output connection is added.

OpenVR HMD remains **POSE_ONLY**, Strong Trusted **UNSUPPORTED**, correction authority
**NO**, and 2B-5P **NOT READY**. HMD remains blocked by its backend. Main MTP provenance
is available. [Phase 2B-5V](position-correction-phase2b5v-main-mount-calibration.md) adds
the dormant mount-to-HIP-center calibration foundation; production MTP is never
relabeled HIP_CENTER. [Phase 2B-5W](position-correction-phase2b5w-calibrated-main-teacher-integration.md)
integrates its position-only teacher directly and binds Main mount calibration identity
to PositionTeacherEpoch, rejecting old-calibration teachers against independent current
context. Production MTP normalization, assignment/calibration selection and runtime
wiring remain deferred.
Raw IMU production adapter/exact space, body-model predictor
handoff and Fixed Calibration implementation remain deferred. Test HIP_CENTER and
prediction lineage, including fixed calibration identity, are explicitly synthetic.

HIL is **NOT REQUIRED / NOT RUN** for this pure dormant boundary. 5S physical HIL remains
a separate pending gate. Software gates are dedicated temporal tests, existing
PositionPrediction contracts, full core/desktop tests, shadowJar and MTP process E2E.

The Phase 2B-5T report's future proposal used endpoint-max skew, oldest-input freshness,
positive-only age limits and additional FULL/HIP checks. The explicit 5U implementation
instruction supersedes that proposal: interval distance, latest-input freshness, four
nonnegative limits and the unchanged existing structural contract are used here.

[Phase 2B-6B](position-correction-phase2b6b-position-error-measurement.md) internally
executes this unchanged boundary and copies the same input into an immutable
teacher-minus-prediction world measurement. Pairable facts remain unchanged and
carry no positions or error. Its Measured result is not learning authority;
runtime/learner/correction law/IK stay disconnected or unimplemented. The earlier
algorithm/error-deferred status above is historical and superseded by 6A/6B.
