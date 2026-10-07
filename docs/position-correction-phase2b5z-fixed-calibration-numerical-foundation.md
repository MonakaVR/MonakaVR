# Phase 2B-5Z — Fixed Calibration Numerical Foundation

`PredictorFixedCalibrationSnapshot` replaces the identity-only fixed calibration
input with an immutable, explicit numerical relation. It is a dormant value
contract. Acquisition, persistence/UI, physical calibration completion, predictor
algorithm and runtime assembly are not implemented by this phase.

## Source audit and anchor definition

`HumanSkeleton.updateHeadTransforms()` copies the positional HEAD tracker's
position into `headBone.setPosition(head.position)` and supplies `head.getRotation()`
to `headBone.setRotation(headRot)` when rotation is available. The absent-rotation
fallback is legacy skeleton behavior, not an allowed Raw HMD predictor substitute.
`assembleSkeleton()` makes HEAD the parentless root of HEAD -> NECK -> UPPER_CHEST
-> CHEST -> WAIST -> HIP. `Bone.setPosition` writes that root joint; it is neither
the HEAD tail/NECK joint nor `computedHeadTracker`.

The HEAD anchor's **reference axes** are the axes supplied to `Bone.setRotation`,
before its multiplication by `rotationOffset`. `SkeletonConfigManager.computeNodeOffset`
defines HEAD geometry as `(0,0,headShift)` and the center-chain lengths along -Y.
`HumanSkeleton.updateNodeOffset` converts those configured directions into a
geometric bone rotationOffset; `Bone` represents its tail along -Y. In particular,
the global HEAD bone rotation includes this geometric encoding and is not itself
the unmodified HMD reference rotation for a nonzero headShift. This offset is body
geometry encoding, not HMD device-to-anatomical calibration. The anchor's position
and reference axes are consistent with `HipBodyModelSnapshot`'s configured local
directions. A source regression checks the copied root position, recovered
reference rotation and the +Z HEAD tail displacement using a synthetic tracker.

The legacy path therefore implicitly identifies HMD/HEAD tracker origin and
reference axes with the model root anchor. Source establishes this convention;
it does not measure the anatomical relationship of a real headset. 5Z makes that
relationship an explicit caller-supplied value. It never captures calibration
from HumanSkeleton, computed trackers, Main, resolver, Background IK or output.

## Rigid relation v1

| Symbol/field | Physical meaning |
| --- | --- |
| H | raw HMD tracking origin and local reference axes |
| A | predictor model HEAD root origin and reference axes, before geometric rotationOffset |
| W | exact common CoordinateSpace of the admitted raw inputs |
| hmdToHeadAnchorLocalOffset / t_HA | metres from H origin to A origin, expressed in H-local axes |
| hmdToHeadAnchorOrientation / q_HA | Hamilton rotation q(H <- A), maps A-local vectors into H-local axes |

Future calibration application must obey:

```text
p_W_A = p_W_H + q(W <- H).sandwich(t_HA)
q(W <- A) = q(W <- H) * q(H <- A)
```

These equations fix translation direction and orientation composition. There is
no production application helper, body chain traversal or HIP_CENTER calculation
in 5Z. Raw HMD pose orientation semantics and exact-space admission are unchanged.

## Snapshot and identity

The public snapshot follows the public `MainDecoupledHipInput`/body snapshot
boundary. Its private constructor retains only final immutable values:
`identity`, `hmdSourceId`, `bodyModelId`, `sessionEpoch`,
`hmdToHeadAnchorLocalOffset`, `hmdToHeadAnchorOrientation`. Repository Vector3
and Quaternion are immutable value classes with final Float components.
There is no data-class copy, identity-only compatibility path or mutable provider.

`create(calibrationId, sessionEpoch, hmdSourceId, bodyModelId, offset, orientation)`
has no defaults. It returns typed Available(snapshot) or Unavailable(reason).
Blank IDs/session, nonfinite translation/orientation and zero quaternion reject.
All finite nonzero quaternion magnitudes, including subnormal values, are valid;
there is no arbitrary tiny-value cutoff. The normalization strategy audited in
5V divides each component directly by the maximum absolute component before
ktmath unit normalization. The scaled squared norm lies in [1,4], avoiding
overflow/underflow and Float reciprocal overflow for subnormal input. A postcheck
fails closed on invalid normalization. Existing 5V behavior is unchanged.

After normalization, the first nonzero component in [w,x,y,z] is positive.
Every quaternion zero becomes +0. Thus q and -q store identical component bits
and yield identical content identity. Translation retains exact raw Float bits,
including distinct +0/-0, following HipBodyModelSnapshot and Main mount precedent.
Finite unusual offsets are preserved without clamping.

`FixedCalibrationIdentity(calibrationId, epoch)` remains a complete identity DTO.
The snapshot factory exclusively owns its effective epoch; callers cannot supply
an effective epoch to create a snapshot or change numbers while retaining it.
Schema `predictor-fixed-v1` serializes length-prefixed HMD source ID, body model ID
and session epoch, followed in fixed order by 8-digit lowercase IEEE-754 raw bits
of offset x/y/z and canonical normalized quaternion w/x/y/z. Opaque delimiters in
IDs cannot create ambiguity. No hash, object identity, counter or timestamp is used.
calibrationId is the human/config namespace and is retained separately; different
IDs with equal content epoch remain distinct complete identities.

sessionEpoch denotes the caller's physical fit/recalibration or loaded calibration
session. Refit, strap/interface changes, explicit recalibration or a different
loaded session must change it even for an equal transform. Samples, sequences,
pose numbers, generatedAt and support-window progression do not change it.
Zero translation and identity orientation are legal only through explicit create;
there is no implicit production identity/default calibration.

## Binding, prediction lineage and responsibility separation

`MainDecoupledHipInput.fixedCalibration` holds the snapshot itself and requires
its hmdSourceId to equal rawHmd.source.sourceId and bodyModelId to equal
bodyModel.identity.modelId. Constructors and data-class copy fail closed on
mismatch. The current body factory owns one v1 model ID; a calibration for a
future model ID cannot silently enter that input. Body content epoch is excluded
from calibration: changing lengths within the same model ID reuses calibration
while changing bodyModelEpoch and overall prediction epoch.

`PositionPredictionEpoch` retains **fixedCalibrationId + fixedCalibrationEpoch**.
Input.epoch() projects both directly from the factory identity. Full equality
and existing expectedPredictionEpoch preflight reject an old calibration
prediction as prediction_epoch_mismatch even at equal predicted positions.

| Independent responsibility | Separate lineage |
| --- | --- |
| Main raw mount -> HIP_CENTER | MainTrackerMountCalibrationIdentity in PositionTeacherEpoch only |
| Raw IMU fixed mount/reset -> body reference orientation | rawImu.provenance.calibrationEpoch; no duplicate IMU transform in fixed calibration |
| Raw HMD provider/frame calibration | rawHmd.provenance.calibrationEpoch, separate from anatomical fit |
| Raw HMD reconnect/mapping | rawHmd.provenance.sourceEpoch/mappingRevision; same physical source ID may reuse fixed snapshot |
| Body geometry | bodyModelId binds anchor schema; bodyModelEpoch independently binds lengths |
| World frame/proof | raw input/provenance space and prediction coordinateSpace; local calibration contains no space |

Fixed calibration changes leave Main teacher/mount identity and Raw IMU
calibration unchanged. Local calibration is no CoordinateSpace proof and never
relaxes exact-space, raw-source or feedback-exclusion requirements. No Main-derived
q_corr, IMU mounting quaternion, learner state or solver tuning enters this value.

## Verification and deferred work

Dedicated tests cover validation, extreme/subnormal normalization, all four
canonical sign branches, signed zeros, each numeric component, session/source/model
content, delimiter ambiguity, snapshot immutability, full identity projection,
source/model mismatch, body content reuse, reconnect/provider/IMU/space separation,
sample progression and stale calibration rejection through actual calibrated
teacher/temporal pairing boundaries. Test fixtures now create explicit bound
numerical calibration rather than invented identity epochs. Usage audit confirms
there is no production MainDecoupledHipInput constructor caller or calibration
factory consumer. Existing runtime/learner/correction/IK paths are untouched.

Fresh dedicated/full Core/Desktop, shadowJar and 11-stage MTP process E2E receipts
and Git/protected-patch evidence are retained in
`build/reports/phase2b5z-fixed-calibration-numerical-foundation-20261007/report.md`.

Fixed numerical calibration is **DORMANT**. Acquisition and persistence/UI are
**NOT IMPLEMENTED**. Raw IMU runtime, predictor runtime, temporal pairing runtime,
Position Correction runtime and correction IK writeback are **NOT CONNECTED**.
Predictor algorithm, learner and correction law are **NOT IMPLEMENTED**.
OpenVR HMD remains **POSE_ONLY**, Strong Trusted **UNSUPPORTED**, production Raw
HMD **BLOCKED BY BACKEND**, 2B-5P **NOT READY**. 5S physical HIL remains pending.
5Z HIL is **NOT REQUIRED / NOT RUN**: no acquisition or hardware authority is added.

Next phase recommendation: **Pure MainDecoupledHipPredictor Algorithm**. All four
predictor input numerical contracts now exist for synthetic testing; a pure
algorithm/contract can proceed without claiming trusted production HMD readiness.
Runtime Input Assembly remains constrained by the independent HMD backend blocker.
