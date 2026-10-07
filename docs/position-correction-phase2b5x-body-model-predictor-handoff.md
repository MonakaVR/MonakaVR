# Phase 2B-5X: Body model predictor handoff foundation

The identity-only body input is replaced by `MainDecoupledHipInput.bodyModel:
HipBodyModelSnapshot`. The existing immutable snapshot binds all six geometry
values and their factory-owned `BodyModelIdentity` together. There is no parallel
body identity field, compatibility constructor, dummy geometry or additional
handoff wrapper. Usage audit found no production caller of MainDecoupledHipInput
or PositionPredictionEpoch; only core test fixtures require migration.

The existing [snapshot factory](position-correction-phase2b2-body-model.md) and
[live publication](position-correction-phase2b2-live-publication.md) are unchanged.
The input holds no SkeletonConfigManager, HumanPoseManager, HumanSkeleton, Bone,
Node, Tracker, Main observation or solved pose. A future predictor can directly
read `input.bodyModel.headShift`, `neckLength`, `upperChestLength`,
`chestLength`, `waistLength` and `hipLength` from the supplied value.

## Geometry and availability

| Field | Config offset | Configured local direction |
| --- | --- | --- |
| headShift | HEAD | +Z shift |
| neckLength | NECK | -Y chain length |
| upperChestLength | UPPER_CHEST | -Y chain length |
| chestLength | CHEST | -Y chain length |
| waistLength | WAIST | -Y chain length |
| hipLength | HIP | -Y chain length |

Values retain Slime configuration/runtime distance units (metres, with UI
centimetre conversion) and local bone axes. They are configured geometry, not
solved world displacements or an HMD-to-HIP transform. The existing private
constructor/factory rejects NaN and infinities; no duplicate validation or
anatomical clamp is introduced. Negative finite lengths, zeros, signed zero and
unusual finite geometry remain representable.

Future input assembly must read `config.currentHipBodyModelSnapshot()` once.
Available supplies its snapshot directly to the input; Unavailable means
**no predictor input**. The constructor accepts neither result wrappers nor
identity-only values. The getter returns one committed immutable tuple in one
volatile read. This does not synchronize geometry with solved skeleton state.

Once assembled, an input keeps that snapshot for its entire prediction lifetime,
including if configuration changes during execution. Only a later assembled input
can receive a newly published snapshot. The predictor must not reread live
publication or reconstruct geometry from six getOffset calls, bones or computed
transforms. There is no runtime assembler, provider, callback, listener, scheduler,
service, duplicate cache or live-read seam in this phase.

## Complete prediction lineage

`PositionPredictionEpoch` now contains both `bodyModelId` and
`bodyModelEpoch`, consistent with its existing primitive projection style.
`MainDecoupledHipInput.epoch()` projects exactly
`bodyModel.identity.modelId` and `bodyModel.identity.epoch`; it never
recomputes content identity. Both fields participate in data-class equality,
nonblank validation and expectedPredictionEpoch matching.

The current factory model ID remains `monaka:slimevr:hip-body-model:v1`, with
the canonical `hip-body-v1;...` raw-Float-bit epoch. Any of the six content
changes alters prediction lineage at identical HMD/IMU poses. Distinct model IDs
with an equal epoch string also differ. Same content from separate objects,
repeated offline capture or live publication has identical lineage. Revert
A -> B -> A restores A; +0 and -0 remain distinct.

`hipBodyModelPublicationSequence` is observation metadata and is absent from
prediction lineage. Re-setting the same relevant value may advance sequence but
keeps identity/epoch. HMD/IMU physical sequence/time, prediction sequence,
generatedAt and support-window progression likewise do not change body identity.
No object identity, hashCode, counter or capture path enters the epoch.

Body geometry/identity belongs only to prediction lineage. Main mount calibration
belongs only to teacher lineage. The [5W calibrated teacher and pairing
boundary](position-correction-phase2b5w-calibrated-main-teacher-integration.md)
is unchanged. At equal numeric positions, an old prediction after body content or
model ID change is rejected as STRUCTURAL_INELIGIBLE /
prediction_epoch_mismatch, following existing structural precedence. Teacher
epoch is unchanged. Pairable still grants no learning authority.

## Verification and deferred work

BodyModelPredictorHandoffTests exercises direct geometry access by a test-only
diagnostic predictor, all six changes/reverts/signed-zero cases, unchanged raw
poses, full identity, offline/live equivalence, repeated publication, immutable
input through a live update during predict, unavailable/recovery, progression,
unusual finite values and reflection guards. The 5W fixture uses actual snapshot
inputs and calibrated teacher normalization to check stale content/model ID
rejection, unchanged teacher lineage and new prediction acceptance.

Regression gates remain body snapshot/publication, prediction contract,
calibrated pairing, temporal pairing, mount calibration, full Core/Desktop,
shadowJar and all 11 MTP process E2E stages. Fresh evidence is recorded in
`build/reports/phase2b5x-body-model-predictor-handoff-20261007/report.md`.

Body model live publication is AVAILABLE; predictor handoff is a DORMANT CONTRACT.
Predictor algorithm, learner and correction law are NOT IMPLEMENTED. Predictor,
temporal pairing, Position Correction runtime and correction IK writeback are
NOT CONNECTED. Raw IMU production adapter/exact-space semantics and Fixed
Calibration numerical semantics remain deferred. OpenVR HMD stays POSE_ONLY,
Strong Trusted UNSUPPORTED, 2B-5P NOT READY. 5S physical HIL remains pending;
this dormant contract requires no HIL (NOT REQUIRED / NOT RUN).

The recommended next phase is **Raw IMU Production Boundary**. The current
RawImuOrientationInput validates a synthetic value/provenance contract but has no
production adapter supplying exact space and independent raw orientation evidence.
The now-ready body handoff does not resolve that input prerequisite. Keep the
next boundary dormant until the other predictor/runtime gates are satisfied.

Phase 2B-5Y adds the [dormant Raw IMU production boundary](position-correction-phase2b5y-raw-imu-production-boundary.md): shared physical acceptance capture, independent fixed mount/reset orientation and separate explicit exact-space binding. Phase 1 q_corr remains excluded; runtime assembly stays disconnected.

Phase 2B-5Z adds the [fixed numerical calibration snapshot](position-correction-phase2b5z-fixed-calibration-numerical-foundation.md), bound to the body model ID and HMD source. Its HEAD anchor is the root position/reference axes before geometric Bone.rotationOffset. Body length/content changes remain separate in bodyModelEpoch and can reuse fixed calibration. The identity-only fixed input is removed; both calibration ID and effective epoch enter prediction lineage. No predictor algorithm/runtime is connected.

Phase 2B-6A implements the [pure Main-decoupled HIP predictor](position-correction-phase2b6a-pure-main-decoupled-hip-predictor.md) from these four numerical inputs. HEAD/NECK use calibrated HMD orientation; UPPER_CHEST through HIP use independent Raw IMU body orientation. HIP_CENTER is the central hipBone tail. MainDecoupledHipInput now requires caller-owned predictionSequence, excluded from epoch, and the predictor requires explicit per-source age/skew limits with no defaults. Earlier algorithm-deferred status in this phase is superseded by 6A; runtime assembly, learner, correction law and IK remain disconnected/unimplemented, and the production HMD backend blocker remains.
