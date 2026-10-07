# Phase 2B-6A — Pure MainDecoupledHipPredictor Algorithm

`PureMainDecoupledHipPredictor` implements a deterministic, stateless production
algorithm over exactly Raw HMD, Raw HIP IMU, immutable body geometry and fixed
HMD-to-HEAD calibration. Its only retained value is immutable explicit temporal
policy. There is no production runtime caller or input assembler.

## Source-grounded central chain

`HumanSkeleton.updateHeadTransforms()` copies the head tracker's position to the
HEAD root and assigns its orientation to HEAD and, without a separate neck
tracker, NECK. `hasSpineTracker` includes HIP. With only HIP present,
`updateSpineTransforms()` selects that rotation for UPPER_CHEST, CHEST, WAIST and
HIP. Extended spine/pelvis models, leg estimation and interpolation are excluded
from this baseline. No HMD/IMU slerp is invented.

`SkeletonConfigManager.computeNodeOffset()` defines HEAD as `(0,0,+headShift)`
and the remaining five segments as `(0,-length,0)`. `updateNodeOffset()` encodes
these vectors into Bone.length and rotationOffset; `Bone.setRotation()` applies
reference orientation before geometry rotationOffset. `TransformNode` propagates
tail positions while preserving each bone's supplied world reference rotation.
The pure algorithm rotates the original signed configured vectors directly.
Negative, zero, signed zero and unusual finite geometry receive no abs/clamp.

`HIP_CENTER` is the **central `hipBone.getTailPosition()` physical point** before
lateral and tracker branches. HIP_TRACKER, computed human://WAIST, HIP_OFFSET,
SKELETON_OFFSET and virtual output positions are not prediction inputs or goldens.

The 5Y source audit confirms
`getCorrectionReferenceRotationFrom(getRawRotation())` applies only fixed
mount/reset transforms, aligning heading/attitude axes with body/bone reference
space. It excludes constraintFix, Stay Aligned, drift, filtering and Main-derived
q_corr. No additional unrepresented HIP fixed transform is needed.

## Frames and equations

W is the exact common CoordinateSpace; H is the raw HMD tracking frame; A is the
model HEAD root reference frame before geometry rotationOffset; B is HIP/body
reference space supplied by independent Raw IMU.

```text
q_WH = safeUnit(rawHmd.orientation)
q_WB = safeUnit(rawImu.orientation)
t_HA = fixed.hmdToHeadAnchorLocalOffset  (H origin -> A origin, in H axes)
q_HA = fixed.hmdToHeadAnchorOrientation = q(H <- A)
p_WA = rawHmd.position + q_WH.sandwich(t_HA)
q_WA = safeUnit(q_WH * q_HA)

p_W_HIP = p_WA
        + q_WA.sandwich((0,0,+headShift))
        + q_WA.sandwich((0,-neckLength,0))
        + q_WB.sandwich((0,-upperChestLength,0))
        + q_WB.sandwich((0,-chestLength,0))
        + q_WB.sandwich((0,-waistLength,0))
        + q_WB.sandwich((0,-hipLength,0))
```

Normalization uses maximum absolute component scaling with direct component
division, as audited in 5V/5Z, followed by unit normalization and finite/nonzero
postchecks. Existing factories are unchanged. Inputs are immutable and unmodified.
Nonfinite rotated translation, anchor, rotated segment or accumulated position
returns UNAVAILABLE(HIP, input.space), without clamping, defaults or identity
substitution. Intermediate overflow rejects even if later terms could cancel.

## Temporal coherence and provenance

`MainDecoupledHipPredictorPolicy(maxInputSkewNanos, maxHmdSampleAgeNanos,
maxImuSampleAgeNanos)` requires all three nonnegative values. **No defaults** or
canonical HIL tuning are supplied. Zero means exact-only. Future samples reject
at the input constructor and are also guarded explicitly by the algorithm.
Each age and the HMD/IMU skew must be <= its own limit (inclusive). Ordered
subtraction of nonnegative timestamps is safe across [0, Long.MAX_VALUE]. Latest
input freshness alone cannot hide an old individual sample.

The output is HIP / HIP_CENTER in `input.space`, with exactly RAW_HMD, RAW_IMU,
BODY_MODEL and FIXED_CALIBRATION dependencies. Provenance is fixed to:

```text
predictionSequence = input.predictionSequence (mandatory caller-owned Long >= 0)
generatedAtNanos = input.nowNanos
inputEarliestAtNanos = min(hmd.sampleAtNanos, imu.sampleAtNanos)
inputLatestAtNanos = max(hmd.sampleAtNanos, imu.sampleAtNanos)
inputHmdSequence / inputHmdSampleAtNanos = exact rawHmd.provenance.sequence / sampleAtNanos
inputImuSequence / inputImuSampleAtNanos = exact rawImu.provenance.sequence / sampleAtNanos
epoch = input.epoch()
```

predictionSequence is caller-owned invocation progression, absent from epoch and never physical dedupe authority. Since 6C each raw physical identity is copied separately and provenance requires support to equal the exact min/max of individual times. No raw-sequence/timestamp
hash, mutable counter, clock reread, cache or recovery state is used.

## Software verification and remaining gates

Dedicated tests cover analytic identity and noncommuting rotation goldens,
calibration translation/orientation, pitch/roll ownership, all six signed geometry
fields, nonunit inputs, deterministic sequence/time/lineage, individual ages,
skew, inclusive and zero limits, Long boundaries, future constructor rejection,
and numeric overflow. Eight actual HumanSkeleton golden cases use only head and
HIP rotation trackers, disabled constraints/IK/extended models/leg effects and
known geometry. Identity and nonidentity fixed calibration cases compare only
central hipBone tail; legacy head input receives the calibrated anchor pose.
Nonzero virtual placement offsets establish separation from the central point.

The dormant integration test runs an actual physical-fixture 5Y IMU boundary,
synthetic HMD, snapshots and pure predictor, then actual 5V Main normalization,
structural teacher preflight and 5U temporal pairing. Distinct positions can be
Pairable. Pairable is compatibility facts only, with **no learning authority**,
error calculation, correction/state update or IK authority. PositionPrediction
remains derived data, not a PoseObservation, and is not registered with the
pipeline, resolver, IK or output.

Pure predictor algorithm: **IMPLEMENTED**. Predictor runtime, Raw IMU runtime
assembly, temporal pairing runtime, Position Correction runtime and correction
IK: **NOT CONNECTED**. Bounded numerical learning law: **IMPLEMENTED / DORMANT** in 6C;
runtime learner: **NOT CONNECTED**. The [6D application contract](position-correction-phase2b6d-application-contract.md)
is **IMPLEMENTED / DORMANT**; actual correction application runtime: **NOT CONNECTED**.
OpenVR HMD: **POSE_ONLY**; Strong Trusted: **UNSUPPORTED**; production Raw HMD:
**BLOCKED BY BACKEND**; 2B-5P: **NOT READY**. 5S physical HIL remains pending.
6A HIL: **NOT REQUIRED / NOT RUN**, because this is a pure disconnected algorithm
and no Strong Trusted HMD is available. Software goldens are not physical HIL.

Fresh test/build/E2E, source audit, protected-patch and Git receipts:
`build/reports/phase2b6a-pure-main-decoupled-hip-predictor-20261007/report.md`.

[Phase 2B-6B](position-correction-phase2b6b-position-error-measurement.md) now supplies
the pure immutable PositionErrorSample boundary. It executes real pairing
internally against the same input, retaining exact teacher/prediction lineage,
sample identity and distinct physical/generation/evaluation times. It copies
positions and calculates teacher minus prediction in exact world space, rejecting
nonfinite subtraction. This is Main-derived comparison measurement, never a
predictor input or learning/correction/IK authority. Predictor API, dependencies
and algorithm are unchanged; runtime remains constrained by the HMD blocker.
[Phase 2B-6C](position-correction-phase2b6c-bounded-learning-law.md) implements
world correction with teacher physical learning dt, physical dedupe and full epoch
invalidation, explicit policy without defaults, bounded update/hold/decay/recovery.
No state or TRACKING result alone is IK authority. 6D separately validates the
current prediction/state/assignment/fallback and creates an absolute solver-facing
candidate. Next is 6E, the dormant/injected runtime orchestration foundation;
runtime and HMD production blockers remain unchanged.
