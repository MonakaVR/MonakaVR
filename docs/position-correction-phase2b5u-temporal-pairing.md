# Phase 2B-5U: Position temporal pairing boundary

`PositionTemporalPairing.check(input, expectedTeacherEpoch, policy)` adds an internal,
stateless production boundary after the unchanged `PositionCorrectionTeacherEligibility`.
`eligible_for_pairing` remains structural preflight. `Pairable` means the sample and
prediction are comparison candidates in time and current context. **Pairable does not
mean learning is allowed.** The only callers are dedicated core tests.

## Time and explicit policy

All timestamps are already in Monaka's local nonnegative monotonic domain. The boundary
does not read a clock, map a remote timestamp, cache a sample or mutate state.

| Meaning | Field |
| --- | --- |
| Teacher physical sample T | `input.rawMainPositionObservation.provenance.sampleAtNanos` |
| Prediction physical support [E,L] | `prediction.provenance.inputEarliestAtNanos`, `inputLatestAtNanos` |
| Prediction generation G | `prediction.provenance.generatedAtNanos` |
| Now N | `input.nowNanos` |

`PoseObservation.observedAtNanos` can be publication/read time and never substitutes for
teacher physical time. Generation is not input freshness. Neither sequence is a time.

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
session epoch, nullable mapping revision, exact space, assignment generation and body
reference. `PositionTeacherEpoch.from(input)` extracts existing fields, returning null
for missing provenance/space/body reference, UNKNOWN body reference, invalid assignment
generation or origin/source ID disagreement. Extraction alone proves neither eligibility
nor current context.

The caller must supply a separately established **current expected teacher epoch** from
current assignment/backend context. Extracting an old sample's epoch and reusing it as
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
HIP_CENTER; matching TRACKER_MOUNT references cannot pair. No transform is performed.

## Deterministic rejection precedence

The unchanged structural preflight runs first. Every failure returns typed
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
The preflight is not changed to make these defensive branches observable.

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
is available, but mount-to-HIP-center calibration is missing; production MTP is never
relabeled HIP_CENTER. Raw IMU production adapter/exact space, body-model predictor
handoff and Fixed Calibration implementation remain deferred. Test HIP_CENTER and
prediction lineage, including fixed calibration identity, are explicitly synthetic.

HIL is **NOT REQUIRED / NOT RUN** for this pure dormant boundary. 5S physical HIL remains
a separate pending gate. Software gates are dedicated temporal tests, existing
PositionPrediction contracts, full core/desktop tests, shadowJar and MTP process E2E.

The Phase 2B-5T report's future proposal used endpoint-max skew, oldest-input freshness,
positive-only age limits and additional FULL/HIP checks. The explicit 5U implementation
instruction supersedes that proposal: interval distance, latest-input freshness, four
nonnegative limits and the unchanged existing structural contract are used here.
