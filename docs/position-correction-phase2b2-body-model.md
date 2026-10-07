# Phase 2B-2: immutable HIP configuration geometry and content epoch

This phase adds a dormant **configuration boundary**, not a HIP predictor or position-correction runtime. `HipBodyModelSnapshot` copies six finite Float values and a `BodyModelIdentity`. It retains no manager, skeleton, bone, node, tracker, mutable map, current transform or pose. [Phase 2B-5X](position-correction-phase2b5x-body-model-predictor-handoff.md) now passes this snapshot directly through `MainDecoupledHipInput`, with both model ID and content epoch in prediction lineage. Identity alone is not a predictor implementation. Hardware/HIL: **NOT RUN**.

## Geometry audit

`HumanSkeleton.assembleSkeleton()` connects HEAD → NECK → UPPER_CHEST → CHEST → WAIST → HIP. `Bone.attachChild()` attaches a child head to its parent's tail. The central HIP reference is the hip bone's tail before the lateral hip-joint and tracker-attachment branches. `SkeletonConfigManager.computeNodeOffset()` defines:

| Snapshot field | Effective config offset | Configured local offset |
| --- | --- | --- |
| headShift | HEAD | (0, 0, +HEAD) |
| neckLength | NECK | (0, -NECK, 0) |
| upperChestLength | UPPER_CHEST | (0, -UPPER_CHEST, 0) |
| chestLength | CHEST | (0, -CHEST, 0) |
| waistLength | WAIST | (0, -WAIST, 0) |
| hipLength | HIP | (0, -HIP, 0) |

HEAD is a shift, not a vertical length. These are configuration geometry, not measured segment lengths or solved world displacements. No HMD-to-HIP calculation, orientation assignment, body-reference calibration or absolute alignment follows from the DTO. `HumanSkeleton.updateNodeOffset()` can suppress HEAD/NECK when a positional/rotational head input is unavailable; the snapshot intentionally preserves configured geometry instead of capturing this transient tracking-dependent state.

Excluded configuration categories:

- **Tracker attachment/virtual tracker placement:** HIP_OFFSET changes HIP_TRACKER; CHEST_OFFSET and SKELETON_OFFSET change tracker branches. They do not define the central chain. Main TRACKER_MOUNT → HIP_CENTER calibration remains a separate, unimplemented contract.
- **Lateral/limb geometry:** HIPS_WIDTH locates LEFT_HIP/RIGHT_HIP relative to the central HIP; legs, feet, shoulders, arms and hands extend other branches. They are excluded from this HIP-center vertical slice. This version of SkeletonConfigOffsets has hand offsets but no finger offsets.
- **Solver tuning:** SkeletonConfigValues' waist/hip/leg averaging weights and SkeletonConfigToggles' extended models, floor clipping, skating, constraints and other solve choices are not structural geometry. They are excluded.
- **Secondary/cosmetic configuration:** non-chain display or secondary-placement settings are not dumped into the DTO.

## Effective values and identity

`SlimeHipBodyModelSnapshotSource.captureOffline()` reads the six values through `SkeletonConfigManager.getOffset()`, which resolves an absent setting to its current default. Explicit-default and unset-default storage therefore produce identical identity. Current bone positions, computed HIP, Main constraints and post-IK outputs never supply dimensions.

`HipBodyModelSnapshot.create()` rejects NaN and both infinities with `Unavailable(nonfinite_<field>)`; it does not clamp or replace them. It copies finite configuration values verbatim, including signed shifts/zero, and does not claim that finite values prove anatomically correct calibration.

Stable model ID: `monaka:slimevr:hip-body-model:v1`.

Epoch encoding is the canonical ASCII string itself (no digest/dependency):

```text
hip-body-v1;headShift=<8 hex digits>;neckLength=<8 hex digits>;upperChestLength=<8 hex digits>;chestLength=<8 hex digits>;waistLength=<8 hex digits>;hipLength=<8 hex digits>;
```

Each value is `Float.toRawBits()` rendered as exactly eight lowercase hexadecimal digits, in the order above. There is no byte-endian or locale-dependent floating-point formatting. `-0.0` and `+0.0` deliberately have different epochs. Schema and field order are fixed; future field/meaning changes require an intentional model/schema version change. Capture time, setter count, random UUID, sample progression and file modification time are absent. A relevant value change changes epoch; identical content and a revert restore the same epoch. Changes to defaults affect epoch through the captured effective values, not raw storage.

## Thread and capture consistency boundary

The existing mutable EnumMaps have no transaction or versioned multi-offset read. Single/bulk `setOffset(s)`, reset and load mutate them incrementally. HumanPoseManager delegates these operations; RPC change-config invokes setters directly, while reset requests use the server queue. ProtocolAPI dispatch does not universally queue handlers onto VRServer. AutoBone applies offset sets through HumanPoseManager and also uses private scratch configurations on processing workers. UserHeightCalibration resets through HumanPoseManager. These paths do not establish a common atomic capture boundary for a live config.

Consequently **live/attached on-demand capture remains unsupported**, even if a caller happens to run on the server thread. An attached manager returns `offline_capture_requires_detached_config`. A detached manager records its construction thread for this read-only boundary; capture on a different thread returns `offline_capture_wrong_thread`. [Phase 2B-2.1](position-correction-phase2b2-live-publication.md) instead adds a committed immutable live mirror, returned by `currentHipBodyModelSnapshot()` in one atomic read; it does not capture legacy storage on demand.

Offline callers must exclusively own the detached manager, including every mutation, on that thread, and capture only between completed configuration operations. They must not share it with RPC, AutoBone or another writer. This is an explicit ownership precondition, not a claim that legacy setters gained thread safety. The thread check cannot make an illegally shared manager atomic. Under the ownership contract there is no concurrent writer and therefore no torn snapshot. The immutable result can subsequently be shared without sharing the manager. No optimistic read falsely claims consistency across an unversioned bulk update.

Phase 2B-2 added no global lock, setter behavior change, periodic runtime capture or predictor seam. Phase 2B-2.1 supplies logical-operation publication with a short mirror-only commit lock and retains legacy side-effect order; it does not synchronize the whole configuration subsystem or solved Skeleton pose. Copying values from a currently solved skeleton is not an alternative.

## Software verification and remaining work

Tests cover every relevant field, all excluded offsets (including attachments and HIPS_WIDTH), all solver values/toggles, repeated capture, default storage equivalence, bulk update/reset/load, immutable old snapshots, content revert, raw-bit precision/signed zero, nonfinite rejection, detached ownership checks and structural immutability. An actual HumanPoseManager/IK test changes tracker and computed HIP poses with Main constraints present/absent and solver enabled/disabled while the separately owned configuration snapshot remains unchanged. No computed pose is fed into capture.

The snapshot contract, offline effective-value adapter and Phase 2B-2.1 live committed publication are complete; predictor runtime is not. Remaining position-phase prerequisites include trusted HMD connection/session lifetime, exact HMD CoordinateSpace/frame identity, coherent HMD position/orientation pairing, Main tracker-mount-to-HIP_CENTER calibration, temporal pairing, predictor algorithm, position correction law and runtime integration. MainFallbackPolicy, Rotation Correction, OutputContinuity, HMD provenance and visible SteamVR behavior are unchanged. Hardware/HIL and SteamVR interactive validation: **NOT RUN**.

Phase 2B-6A implements the [pure Main-decoupled HIP predictor](position-correction-phase2b6a-pure-main-decoupled-hip-predictor.md) from these four numerical inputs. HEAD/NECK use calibrated HMD orientation; UPPER_CHEST through HIP use independent Raw IMU body orientation. HIP_CENTER is the central hipBone tail. MainDecoupledHipInput now requires caller-owned predictionSequence, excluded from epoch, and the predictor requires explicit per-source age/skew limits with no defaults. Earlier algorithm-deferred status in this phase is superseded by 6A; runtime assembly, learner, correction law and IK remain disconnected/unimplemented, and the production HMD backend blocker remains.
