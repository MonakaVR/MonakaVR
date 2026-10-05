# Phase 2B-3: trusted HMD same-message pose envelope

This phase records copied position and decoded orientation from **one Position message**, alongside the Phase 2B-1 historical position record. It establishes same-wire-message coherence, not physical acquisition simultaneity, temporal interpolation, tracking usability or coordinate-frame validity. No predictor, RawHmdPoseInput runtime factory, position learner, correction law, solver injection or output correction is implemented. Hardware/HIL: **NOT RUN**.

## Wire and sender audit

The generated `ProtobufMessages.Position` descriptor contains `tracker_id`, XYZ, quaternion XYZW, `data_source`, and velocity XYZ only. X/Y/Z, data_source and velocity components expose presence. Quaternion scalars do not: explicit orientation component wire presence is not recoverable from this schema. Missing Q decodes as zero; an explicitly encoded zero component is indistinguishable from its omitted default. There is no upstream acquisition timestamp, pose sequence, tracking-universe/frame/recenter identity or transport session ID.

The prepared local `build/direct-driver-source/src/VRDriver.cpp` calls `GetRawTrackedDevicePoses(0.0f, ...)`, selects `poses[index]`, and derives both XYZ and quaternion from that same `pose.mDeviceToAbsoluteTracking`. `GetRotation()` derives components from the matrix using square roots/signs; the sender does not explicitly normalize the quaternion before transmission. When available, the current-universe translation/yaw is applied to both components before all XYZ and Q values are set in one message. The translation/transform identity is not transmitted.

The sender emits FULL for valid tracking and IMU for `TrackingResult_Fallback_RotationOnly`, still with XYZ; otherwise it sends status rather than this pose. The inspected `build/driver-audit` source has the same relevant send path. These are local source audits, **not installed physical driver proof** or proof for every legacy sender. Protocol version 2 alone does not establish component presence, normalized orientation, acquisition timing or frame/session lineage. The server therefore retains raw data_source integer and presence, including unknown values, independently of completeness. FULL maps to FULL, IMU to ROTATION_ONLY, and PRECISION/NONE/unknown/missing map to NONE, preserving the existing receive policy.

## One accepted message, two compatible records

`ProtobufBridge.positionReceived()` preserves the existing order: modality assignment; hasX position write; unconditional `Tracker.setRotation()`; optional hasVx velocity write; dataTick. Immediately after the existing hasX position assignment, `TrustedRawHmdPositionSource.positionMessageAccepted()` allocates one accepted sequence and captures the local receipt clock **once**. It copies decoded Q directly from the message, never Tracker orientation, reset/filter state or the orientation sample counter.

The recorder atomically publishes one immutable holder with:

- `HmdAcceptedPositionSample`: the unchanged position-only historical API;
- `HmdAcceptedPoseMessageSample`: copied position, explicit `PositionComponentPresence`, decoded quaternion, sequence, receipt timestamp, object-lifetime source epoch, trusted ingress identity, data_source integer/presence and modality.

Both records share the acceptance event's sequence, timestamp, source epoch and ingress identity. Each getter reads the volatile holder once, with no live Tracker reads. Two separate getter calls spanning a new acceptance are not a transactional pair; a consumer needing the coherent position/orientation tuple reads `acceptedHmdPoseMessageSample()` once. Math values and presence are immutable; no mutable Tracker or protobuf message reference is retained. Mutations remain on the existing server-thread ingress. Subsequent storage/rotation/reset/solver changes and subsequent messages cannot mutate a prior sample.

`receivedAtSystemNanos` means **local accepted ingress receipt time** at server-thread payload acceptance, including any preceding queue delay. It is not HMD physical acquisition time. No-X messages create neither record and call no acceptance clock. New accepted payloads advance the sequence even for identical numbers. Polling, dataTick, heartbeat, status, battery, velocity-only messages, HumanPoseManager and solver updates do not manufacture new accepted position/pose samples. Existing orientation provenance remains a separate counter advanced by the existing unconditional setRotation call, including no-X messages.

## Structural validity, modality and readiness are separate

`pairingStatus == COMPLETE` requires all explicit X/Y/Z components, finite position, finite quaternion components and a finite squared quaternion norm greater than `1e-10f`, matching Phase 2A numeric validation. A valid nonnormalized quaternion is preserved exactly; no normalization or identity substitution is performed. Numeric structural tests do not prove explicit Q encoding or useful tracking modality.

Partial XYZ remains accepted according to the legacy hasX condition; missing Y/Z still update Tracker storage to decoded zero. The pair has `INCOMPLETE_POSITION` / `hmd_position_components_incomplete`, never a complete physical position inferred from those zeros. Nonfinite position reports `INVALID_POSITION` / `hmd_position_nonfinite`. Zero, near-zero, NaN, Inf or overflowed quaternion norm reports `INVALID_ORIENTATION` / `hmd_orientation_invalid`. All structural reasons are retained if multiple defects coexist; status precedence is incomplete position, invalid position, invalid orientation, complete. Validation is read-only metadata and cannot reject or throw on these legacy numeric writes.

FULL, IMU, NONE, absent and unknown data_source can all carry a structurally complete tuple; modality does not change completeness. Future tracking eligibility must be specified separately. Neither old numeric storage nor a historical COMPLETE sample proves current freshness or connection status.

## Trust and epochs

The Phase 2B-1 explicit SteamVR creation-boundary registration is unchanged: SteamVR origin, remote ID 0, HMD and position/rotation capabilities, not internal, not Monaka output, and FeedbackExclusion. HEAD role alone and unregistered lookalikes are rejected. External SteamVR HMDs with `isComputed=true` remain accepted because this legacy flag does not prove internal solver origin.

Normal messages share one Tracker-object-lifetime source epoch. Object recreation clears both records and starts a new epoch/sequence. Same-object disconnect/reconnect retains the epoch. At the Phase 2B-3 checkpoint, queued messages had no connection identity. [Phase 2B-4a](position-correction-phase2b4a-transport-session-foundation.md) adds separate Windows logical-session handles and queue envelopes, but the HMD DTOs do not consume them yet. **Same-message pairing is not trusted transport-session lifetime.** No Slime reset calibration epoch, HMD universe/recenter identity or CoordinateSpace is inferred.

## RawHmdPoseInput remains blocked

The new COMPLETE DTO has `rawPoseInputEligible=false`, with `hmd_space_unverified`, `hmd_frame_epoch_unavailable`, and `hmd_session_epoch_unavailable`. Its pairing gap is resolved, so it does not report `hmd_pose_pairing_unavailable`. Structural defects add their specific reasons. The old position-only DTO retains its existing reasons, including `hmd_pose_pairing_unavailable`: it remains insufficient by itself, while the new API supplies the separate same-message evidence. Phase 2A requirements are not weakened.

Status at this checkpoint:

| Boundary | Status |
|---|---|
| Trusted accepted position receipt provenance | Established |
| Same-message position/orientation envelope | Established for structurally COMPLETE samples |
| Trusted source object lifetime | Established |
| Windows logical transport-session infrastructure | ESTABLISHED by Phase 2B-4a |
| HMD sample transport-session integration/current filtering | NOT IMPLEMENTED |
| Exact HMD CoordinateSpace / frame-calibration epoch | BLOCKED |
| Runtime-clock mapping / freshness consumer | NOT IMPLEMENTED |
| RawHmdPoseInput runtime-ready | NO |

Other prerequisites remain Main TRACKER_MOUNT-to-HIP_CENTER calibration, temporal pairing with Main, predictor algorithm, position correction law and runtime integration. The separate gate for overlapping cross-thread body-model publication commit order versus legacy config write order remains unresolved; this phase does not touch it.

## Software evidence and unchanged behavior

`TrustedHmdPositionProvenanceTests` uses deterministic receipt clocks and serialized messages through the production receive queue. It covers shared event metadata, immutable tuple retention, exact partial XYZ presence, invalid/nonfinite values, nonnormalized Q preservation, modality/presence separation, no false advancement, trust, lifetime/reconnect limits, one clock call, independent orientation sequence and generated descriptor presence semantics. Existing numeric rotation/position/velocity/status behavior remains tested.

Required gates are Core/Desktop tests, shadowJar and MTP process E2E, including existing Rotation Correction, Hybrid stress/diagnostics, prediction contracts and body-model offline/live publication tests. MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication and visible SteamVR behavior are unchanged. Hardware/HIL: **NOT RUN**.
