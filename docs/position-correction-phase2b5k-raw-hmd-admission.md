# Phase 2B-5K: trusted Raw HMD pose admission

**2B-5R supersession:** The sections below describe the delivered 5K/5M baseline. Current Strong Trusted requirements are defined in [the 5Q/5R support policy](position-correction-phase2b5q-strong-trusted-hmd-support-policy.md). Frame-only Ready evidence no longer admits. Ready is derived from complete sample-bound provider evidence and a reviewed current provider context; ordinary ingress records are separate from trusted candidates. OpenVR always supplies null provider evidence and four explicit capability blockers. The complete gate additionally rechecks provider/raw/output/validity, exact immutable sample binding and current candidate stability. No predictor/correction runtime is connected.

Phase 5K implements an explicit admission proof model and one desktop trusted input boundary. It does not enable a predictor or Position Correction. Current generic OpenVR ingress, including the inspected Virtual Desktop / `oculus` route, has no authoritative pose-level HMD frame reference. Its capability remains **Unavailable**, and trusted input generation remains **fail-closed**. Ordinary HMD position/orientation handling continues independently.

The preimplementation audit found two fixed `rawPoseInputEligible=false` properties and a public structural `RawHmdPoseInput` constructor, with no contextual frame/session/freshness gate. This phase removes those eligibility properties, introduces typed evidence/results, and adds dedicated contract tests. Source receipt, source identity and same-message pairing were already established by [Phase 2B-3](position-correction-phase2b3-hmd-pose-pairing.md) and [Phase 2B-4b](position-correction-phase2b4b-hmd-session-integration.md); they do not establish an HMD frame.

## Capability and scope

`RawHmdPoseInputCapability` in `dev.monaka.tracking.desktop` is a sealed admission proof model:

- `Unavailable`: an immutable, nonempty set of typed blocker reasons. The production generic recorder advertises `FRAME_REFERENCE_UNAVAILABLE`.
- `Ready`: an immutable evidence snapshot with an **internal constructor and internal copy visibility**. It binds expected source ID, Tracker/source epoch, logical transport-session epoch and accepted sample sequence to explicit CoordinateSpace, frame/calibration epoch, optional mapping revision, receipt freshness policy, typed proof kind and proof source.

`RawHmdFrameProofKind` distinguishes `NO_AUTHORITATIVE_FRAME_PROOF` and `EXPLICIT_POSE_BOUND_FRAME_PROOF`. The latter and a nonblank proof source are required. Ready can represent incomplete evidence so admission rejects it normally; constructing the evidence snapshot does not imply acceptance. Ready construction currently occurs only in the dedicated unit fixture. There is no runtime switch, operator override, public Ready factory or production Ready producer.

Session, source/object epoch, sample sequence, universe ID, probe owner, transform hash and diagnostic boundary counter alone cannot establish the frame proof. Source epoch and frame lineage remain separate. A frame may change while source/session/universe stay constant. Evidence for sample N cannot authorize N+1, even when XYZ/Q are identical. A future authoritative producer must bind the facts at provider/backend/sender sample acceptance, before queuing; the receiver only validates them. No current ambient frame state is used to retag a queued or accepted sample.

CoordinateSpace is an explicit evidence value, with nonblank id/convention and nonnegative revision. Equality and value construction alone do not establish physical alignment. Admission performs no coordinate transform or inference. The explicit frame/calibration epoch must be nonblank; it is not derived from transport reconnect, Tracker recreation, mounting calibration, universe, observer lifetime or reset events. An authoritative mapping revision is copied, or remains null; negative revisions reject. No revision zero is substituted.

## One production-facing boundary

`ProtobufBridge.currentRawHmdPoseAdmission()` returns `RawHmdPoseInputAdmission.Accepted(input)` or `Rejected(reasons)`. It uses the recorder's fixed production capability. `rawHmdPoseInputCapability()` exposes diagnostics. An internal evidence seam, `admitCurrentRawHmdPoseInput(capability)`, serves future evidence producers and tests; it uses the identical complete gate, never a partial bypass. Transport types remain in desktop.

Admission is VRServer-thread confined, matching accepted sample publication. Its coherent read sequence is:

1. Read the active `TransportSessionHandle` object.
2. Read the recorder's **current** paired pose candidate for that exact handle epoch. Never fall back to historical storage or mutable Tracker pose.
3. Validate capability, explicit frame evidence, exact source/source epoch/session/sequence, same-message structure, FULL position modality and local receipt freshness.
4. Read the active handle again and require the **same object**, including when two handles would compare equal by epoch string.
5. Return Rejected for any defect. Only after the final check allocate one provenance and one RawHmdPoseInput.

The before/after check detects a session transition during admission. It is not a lease: disconnect can occur immediately after return. Source/pose writes cannot race with the gate under the existing VRServer-thread contract. Atomic active session state may change on the bridge thread. No new transport lock, enqueue ordering, message drop or ambient mutable capability is introduced. Rejected reads do not mutate any legacy Tracker numeric, orientation, velocity, status or modality state.

Both historical getters and both current-session getters remain available. Windows retains enqueue-time logical session binding; Unix remains sessionless and cannot satisfy admission. Old-session or sessionless messages can still update ordinary legacy storage/history while the separate current candidate remains intact. They cannot be relabeled as the active session. A delayed old message does not revoke a distinct valid current candidate, and cannot substitute for it.

`HmdAcceptedPositionSample` cannot produce raw pose input because it lacks paired orientation. `HmdAcceptedPoseMessageSample` carries copied Q from the same decoded message, explicit XYZ presence and structural reasons. Complete XYZ, finite coordinates and finite quaternion components with finite squared norm above `1e-10f` are required. Valid nonnormalized Q is preserved exactly; invalid Q is never normalized to identity. Data source must be explicitly present and supported; both the decoded data source and modality must indicate FULL. Complete numeric XYZ in IMU/ROTATION_ONLY, NONE or inconsistent modality is rejected.

The removed fixed eligibility properties are replaced by `rawPoseMetadataLimitations` on both sample DTOs. These describe context-free gaps in the historical record only. They do not replace the admission result, and may still list space/frame gaps when separate synthetic pose-bound evidence authorizes the same sample.

## Receipt freshness and generated input

`RawHmdPoseFreshnessPolicy(maxReceiptAgeNanos)` requires a positive limit at admission. No canonical HMD limit exists at the audited HEAD; the evidence producer must supply one. The MTP lease/timeout is not reused as an implicit policy.

`receivedAtSystemNanos` and admission's clock share the bridge's local `System.nanoTime()` domain. Phase 5M fixes the original processing-time capture: `ProtobufBridge.messageReceived` samples the injected clock once at decoded-message ingress, immediately before adding an immutable `InboundProtobufEnvelope` to the input queue. The saved time is passed through dequeue, message dispatch and position acceptance into both sample records without reading the clock again. This is **HOST_RECEIVE_MONOTONIC ingress receipt freshness**, including queue wait, scheduling and subsequent processing delay, not physical acquisition freshness or an OpenVR/sender timestamp. Transport buffering and parsing before this decoded-message boundary are outside the measurement. Physical acquisition time remains unknown. Processing without ingress time preserves legacy numeric updates but cannot publish a trusted candidate or substitute an older current sample. The existing core provenance requires nonnegative local time; negative receipt/current values fail closed before arithmetic. For nonnegative time, a future receipt is rejected separately; otherwise `age = now - receipt` is overflow-safe, and `age <= limit` accepts. At receipt 1,000,000 and limit 500, now 1,000,500 accepts, 1,000,501 is stale and 999,999 is future. Polling, heartbeat, ticks, getters and repeated admission never advance the sample sequence/time.

On acceptance, `ObservationSampleProvenance` maps exactly:

| Field | Source |
| --- | --- |
| sequence | accepted paired sample sequence |
| sampleAtNanos | accepted local receipt time; **not physical acquisition time** |
| sourceEpoch | accepted Tracker/source object epoch |
| calibrationEpoch | explicit Ready HMD frame/calibration epoch |
| mappingRevision | authoritative evidence revision, or null |
| space | explicit Ready CoordinateSpace |

`RawHmdPoseInput` receives the accepted ingress identity, copied accepted XYZ and same-message Q, that space and that provenance. No mutable Tracker read, IK, Main-derived correction, normalization or numerical transform occurs. The core constructor remains a structural value contract for core/unit fixtures; direct construction does not establish trusted desktop admission. All production desktop generation uses the one gate.

## Stable rejection codes

Reasons are typed enum values with stable string codes. Rejection and Unavailable sets are copied and unmodifiable; enumeration order provides deterministic diagnostics. Multiple defects are preserved.

| Reason | Code |
| --- | --- |
| CAPABILITY_UNAVAILABLE | `hmd_capability_unavailable` |
| FRAME_REFERENCE_UNAVAILABLE | `hmd_frame_reference_unavailable` |
| FRAME_SPACE_UNAVAILABLE | `hmd_frame_space_unavailable` |
| FRAME_SPACE_INVALID | `hmd_frame_space_invalid` |
| FRAME_EPOCH_UNAVAILABLE | `hmd_frame_epoch_unavailable` |
| POSITION_UNAVAILABLE | `hmd_position_unavailable` |
| SOURCE_IDENTITY_MISMATCH | `hmd_source_identity_mismatch` |
| SOURCE_EPOCH_MISMATCH | `hmd_source_epoch_mismatch` |
| SESSION_EPOCH_UNAVAILABLE | `hmd_session_epoch_unavailable` |
| SESSION_EPOCH_MISMATCH | `hmd_session_epoch_mismatch` |
| CURRENT_SESSION_CHANGED | `hmd_current_session_changed` |
| SAMPLE_SEQUENCE_MISMATCH | `hmd_sample_sequence_mismatch` |
| SAMPLE_PROVENANCE_INVALID | `hmd_sample_provenance_invalid` |
| POSITION_COMPONENTS_INCOMPLETE | `hmd_position_components_incomplete` |
| POSITION_NONFINITE | `hmd_position_nonfinite` |
| ORIENTATION_INVALID | `hmd_orientation_invalid` |
| POSITION_MODALITY_NOT_FULL | `hmd_position_modality_not_full` |
| DATA_SOURCE_UNAVAILABLE | `hmd_data_source_unavailable` |
| DATA_SOURCE_UNSUPPORTED | `hmd_data_source_unsupported` |
| RECEIPT_TIME_INVALID | `hmd_receipt_time_invalid` |
| FUTURE_RECEIPT_TIME | `hmd_future_receipt_time` |
| SAMPLE_STALE | `hmd_sample_stale` |
| FRESHNESS_POLICY_INVALID | `hmd_freshness_policy_invalid` |
| MAPPING_REVISION_INVALID | `hmd_mapping_revision_invalid` |
| FEEDBACK_SOURCE_NOT_ALLOWED | `hmd_feedback_source_not_allowed` |
| PROVIDER_SESSION_UNAVAILABLE | `hmd_provider_session_unavailable` |
| OBSERVATION_ID_UNAVAILABLE | `hmd_observation_id_unavailable` |
| RAW_SPACE_GENERATION_UNAVAILABLE | `hmd_raw_space_generation_unavailable` |
| PROVIDER_EVIDENCE_INVALID | `hmd_provider_evidence_invalid` |

## Verification and production status

`RawHmdPoseAdmissionTests` covers the explicit success fixture, rejection matrix, field/provenance copying, identity independence, same-numeric/new-sequence binding, FULL vs IMU, long-range arithmetic and exact freshness boundaries, current-slot versus delayed/sessionless history, immutable reasons and legacy processing. Deterministic injected clocks change or close the active session during admission; no sleep or new production test hook is needed. A production tripwire requires Unavailable even for a current COMPLETE FULL generic HMD pose. Existing provenance/session tests retain their prior contracts with the renamed metadata-only diagnostics.

Required gates are targeted admission/session/core contract tests, all Core/Desktop tests, shadowJar and MTP process E2E. HIL and optional upstream mock-suite are **NOT RUN**. Synthetic acceptance establishes software validation, not physical frame proof.

Software verification on 2026-10-07 (Asia/Tokyo): targeted admission 40, inbound session 20, HMD provenance 16 and core prediction contract 13 tests passed. The full command `:server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --rerun-tasks --offline --no-daemon --console=plain` passed: Core 681 tests, Desktop 92 tests, zero failures/errors/skips; shadowJar and all 11 process E2E stages passed. JDK 17 and the repository wrapper were used. These are software receipts, not HMD/native transport or physical frame proof.

| Boundary | Status |
| --- | --- |
| Typed capability model | IMPLEMENTED |
| Trusted current-session admission gate | IMPLEMENTED |
| Production authoritative frame capability | UNAVAILABLE |
| Production RawHmdPoseInput generation | BLOCKED / FAIL-CLOSED |
| Synthetic Ready admission | UNIT-VERIFIED; fixture only |
| CoordinateSpace inference | NONE |
| HMD frame/calibration epoch inference | NONE |
| Trusted production HMD frame epoch | NOT YET TRUSTED |
| Physical acquisition timestamp | UNAVAILABLE / UNKNOWN |
| Frame-lineage protocol | HOLD; no protobuf/MTP/native changes |
| Predictor / Position Correction runtime | NOT IMPLEMENTED / NOT WIRED |

MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication, HMD conversion formula and universe cache semantics remain unchanged. This phase adds no driver replacement, reset/recenter, SteamVR restart or hardware action.

Remaining prerequisites include an authoritative pose-level frame producer and its failure/change semantics, shared-space alignment/calibration, independent prediction input modes, Main mount-to-HIP_CENTER calibration, temporal pairing, predictor algorithm and Position Correction law. Recommend the next bounded phase as **2B-6A: independent-position-source / predictor input-mode architecture under fallback C**. The current core input still requires raw HMD; alternate-anchor math and API are future design work, not silently enabled by this gate.

**PROVEN BY SOURCE**: capability/gate structure and unchanged ordinary receive path. **UNIT-VERIFIED** must refer to actual executed test receipts. **FAIL-CLOSED IN PRODUCTION**: no frame producer, no Ready advertisement. **HIL-UNVERIFIED**: hardware/SteamVR transport not run. **UNKNOWN**: physical frame lineage, acquisition time and physical source alignment.

Historical naming note: the 5K-era **2B-6A: independent-position-source / predictor input-mode architecture** recommendation above was never executed as an implementation phase. It is a historical unexecuted recommendation superseded by later 5Q-5Z work. The official delivered [Phase 2B-6A — Pure MainDecoupledHipPredictor Algorithm](position-correction-phase2b6a-pure-main-decoupled-hip-predictor.md) uses the four current inputs; it introduces no alternate-anchor mode or HMD admission relaxation.
