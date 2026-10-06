# Phase 2B-5D — Observed HMD pose binding

This phase binds an outgoing HMD Position to copied driver-local observations. It emits no frame lineage over protobuf and creates no CoordinateSpace, HMD calibration epoch, acquisition timestamp, RawHmdPoseInput, predictor or Position Correction runtime. Trusted frame epoch remains **NOT YET TRUSTED**; CoordinateSpace and RawHmdPoseInput remain **BLOCKED**.

## Phase 2B-5C evidence and the problem

The preceding Hardware/HIL result was **PARTIAL**, and protocol design remained HOLD. Limited observations were stationary/motion baseline behavior, reset signals, a natural universe change, observer persistence across transport reconnect, owner replacement across SteamVR restart, and a same-universe reset event with generation increment. Normal HMD motion did not spuriously rotate the generation. Numeric applied-cache changes and lookup failures were not observed.

Exact physical origin transition, equality between the cache and the live frame, exact outgoing wire pose change, complete silent-boundary detection and atomic pose/frame association remain UNKNOWN. Reset events and pose associations interleaved; the diagnostic lacked exact outgoing protobuf XYZ/Q values. A generation read only just before send could therefore appear to associate an earlier raw sample with a newly observed generation.

These are configuration-limited findings from the supplied 2B-5C report, not new HIL evidence. No installed driver is replaced, SteamVR is not restarted, production probe flags are not set, and HIL is NOT RUN in 2B-5D.

## Three software points

1. **A: raw capture marker.** Immediately after the existing `GetRawTrackedDevicePoses(0.0f, poses, std::size(poses))` returns, the enabled adapter locks its diagnostics mutex and copies observer state. The runtime call itself is outside that lock. An event may be observed during the raw query or before the marker lock is acquired: that earlier interval is not covered by this comparison.
2. **B: final binding.** After the unchanged conversion and all Position setters, the enabled HMD branch acquires the same mutex, performs its final `Observe`, and constructs an immutable binding.
3. **C: bridge invocation.** Logging, if enabled, and the original `bridge_->SendBridgeMessage(*message)` invocation execute in that same mutex scope. Return from the send callback releases the mutex.

This establishes driver-software ordering only against this probe's mapped event observer. It does not freeze SteamVR, make the raw query and frame state atomic, prevent physical resets, establish provider timing, or prove downstream delivery. Events may already have been polled and be waiting for the observer lock.

## Immutable marker and binding contract

`HmdRawPoseCaptureMarker` contains const copied owner namespace, captureOrdinal, observationSequence and detectedBoundaryGeneration. Capture changes neither observationSequence nor boundaryGeneration, performs no runtime query, and does not infer universe identity. The separate ordinal increases for every captured pose-loop transaction, including transactions that produce no HMD Position. A new owner may restart the ordinal at one; no global uniqueness is claimed.

The overlay's optional marker is local to one loop iteration. Reconnect skips raw acquisition while disconnected; an old marker is never carried into a subsequent acquisition, reconnect or owner recreation. The pure core remembers only the current issuance metadata and whether it was consumed. New capture invalidates an unused earlier marker. Reuse, stale ordinal, unissued zero ordinal, or altered issuance sequence/generation fails closed.

`HmdObservedPoseFrameBinding` has const copied capture marker, bound HmdFrameObservation, ownerMatches, captureUsable, optional observedBoundarySinceCapture, boundaryDuringBind, optional lastObservedBoundarySinceCapture, rawPose, wirePose, numeric dataSource and assessment. It contains no runtime/protobuf/cache/logger references. Historical values cannot follow later observations.

Owner match and valid current, unconsumed issuance are prerequisites for comparison:

```text
observedBoundarySinceCapture = bound.detectedBoundaryGeneration != capture.detectedBoundaryGeneration
boundaryDuringBind = generation immediately before final Observe != generation after it
```

Owner or issuance mismatch makes the comparison unavailable. Every BindAndSend still invokes the legacy send callback exactly once regardless of diagnostic assessment. Repeated-marker errors never suppress tracking.

| Assessment | Meaning |
| --- | --- |
| NO_OBSERVED_BOUNDARY_SINCE_CAPTURE | Usable marker, available final observation, unchanged generation. Only absence of an OBSERVED boundary between these software points. |
| OBSERVED_BOUNDARY_SINCE_CAPTURE | Usable marker and changed generation; an event or sampled universe/transform change was observed in the interval. Does not identify the physical frame. |
| CAPTURE_OWNER_MISMATCH | Marker came from another observer namespace; no cross-owner comparison. |
| FRAME_OBSERVATION_UNAVAILABLE | No detected generation change, but final proof is Unknown, TransformLookupFailed or InvalidTransform. |
| CAPTURE_MARKER_REUSED_OR_STALE | Same-owner marker fails the current unconsumed issuance check; comparison unavailable. |

Changed generation takes priority over observation-unavailable assessment so a detected boundary remains visible under bad metadata; FrameProofState independently retains lookup failure/invalid/unknown. Initial baseline can have no observed change and still have FRAME_OBSERVATION_UNAVAILABLE. No assessment is SAFE, TRUSTED, CONTINUOUS or VALID_FRAME.

The final observation's universeChanged/transformChanged/boundarySignalObserved flags retain existing per-observation semantics. Separately, `lastObservedBoundarySinceCapture` copies the most recent boundary snapshot within a usable changed interval. Thus an earlier observed reset/universe transition retains its reason even if the final PoseSample has unchanged inputs. This is not an exhaustive event history: preceding boundary lines and generation deltas remain relevant for multiple events. Several reasons at one final observation still increment generation only once.

## Raw and wire diagnostics

`HmdDiagnosticPose` has const binary32 px/py/pz/qx/qy/qz/qw in XYZ/XYZW order. The raw diagnostic uses the same existing GetPosition/GetRotation locals immediately before the universe transform, without recalculating a quaternion. Raw position is already binary32; the existing binary64 raw quaternion is narrowed to binary32 for this diagnostic sample only. This narrowing does not replace or feed back into the existing binary64 transform math. The f32 trace describes that diagnostic projection, not original matrix bytes or binary64 quaternion bits.

Each wire component is a named local float used both by its original Position setter and by the diagnostic copy. Position expressions and quaternion `(float)` casts retain their original order. Wire q is copied after the existing transform; it is not recomputed for logging. Numeric `position->data_source()` records the existing FULL/IMU choice and does not infer source identity or modality.

Formatting uses `std::bit_cast<uint32_t>` with eight hexadecimal digits per binary32 component. No epsilon, normalization, decimal formatting, quaternion renormalization or identity substitution occurs. Signed zero, subnormals, infinity and NaN payloads in those diagnostic float values are preserved. Raw and wire values are distinct copied samples. Exact wire floats are the values supplied to protobuf, rather than an earlier binary64 approximation.

## Send-path and lock audit

Audit source is pinned driver `dcc0f56bcb2a3196d6f92b1ed1d029faa425b931`, OpenVR `91825305130f446f82054c1ec3d416321ace0072`, and linalg `a3e87da35e32b781a4b6c01cdd5efbe7ae51c737`. The checked source and submodules remain clean and read-only. Reproducible overlays alone are modified.

| Path | Source result |
| --- | --- |
| BridgeClient send | Inherits BridgeTransport::SendBridgeMessage; no override or probe callback. |
| BridgeTransport.cpp: SendBridgeMessage | Checks atomic connected flag, calculates/serializes protobuf, copies into CircularBuffer, signals async handle, returns. No join, condition wait, event pump or RunFrame callback. |
| CircularBuffer.cpp: Push | Capacity check, memcpy and atomic counters. Returns failure when full; never waits for draining. |
| Buffer-full fallback | ResetConnection → BridgeClient::Reconnect → CloseConnectionHandles plus timer setup. No probe, devices mutex or wait for RunFrame. Existing fallback behavior is unchanged. |
| uvw async.ipp / libuv win/async.c | send invokes uv_async_send; the Windows implementation posts a completion and returns. The async event is published by the later event-loop callback, not synchronously by send. |
| Incoming/connect callbacks | OnRecv/OnConnect execute in the bridge event-loop path. They are not called synchronously by SendBridgeMessage, and have no frame-probe hook. OnBridgeConnect starts a separate worker for its initialization wait. |
| RunFrame | Mapped event observation is before the devices mutex scope; no devices lock is held while acquiring the probe mutex. Event vector and downstream haptics are unchanged. |
| Logger.hpp | Formatting and a logger-local mutex, then VRDriverLog::Log. No source-level callback into the probe. Existing logger calls release their mutex before entering other probe operations. The bridge uses a separate logger. |

**Decision: hold the existing dedicated probe mutex across the original HMD send invocation.** The audited bridge has no synchronous path back into ObserveEvent/ObservePose/BindAndSend, and no wait depending on RunFrame acquiring this mutex. No additional device/global lock is acquired. The template callback contract forbids re-entry into the adapter; future bridge changes must repeat this audit. This proves the pinned driver call-chain ordering, not properties of opaque SteamVR runtime internals or all upstream transport concurrency.

An event fully observed between capture and binding raises the bound generation. An event attempting this mutex during BindAndSend cannot mutate the observer until the send callback exits. An event observed after that send advances state for subsequent captures/poses; it cannot retag an immutable held binding.

## Opt-in trace and unchanged behavior

All flags accept only exact string `1` and are read once in Init:

| Flags | Behavior |
| --- | --- |
| MONAKA_HMD_FRAME_PROBE != 1 | No observer, capture owner/string allocation, probe lock or probe logging. Original direct send remains reachable for HMD and non-HMD. Local wire floats add no runtime query or payload change. |
| Main probe on, BINDINGS off | Capture/binding bookkeeping and bind/send mutex ordering operate; ordinary state/event logs and independently enabled POSES associations remain usable. |
| MONAKA_HMD_FRAME_PROBE_POSES=1 | Existing at-most-one-per-second pose-association semantics remain independent; no repurposing. |
| MONAKA_HMD_FRAME_PROBE_BINDINGS=1 | Effective only under main probe. Logs every outgoing HMD binding; high-volume HIL/debug mode for short capture windows only. |

No enabled-mode performance equivalence is claimed. Formatting/logging/send-under-lock can affect scheduling and delay event observation. No network traffic or protobuf field is added.

Stable prefix is `MONAKA_FRAME_PROBE_V1`; the new kind is `pose_binding`. Fields are owner, capture_owner, capture_ordinal, capture_observation_seq, capture_boundary, bind_observation_seq, bind_boundary, owner_match, capture_usable, observed_boundary_since_capture (true/false/unavailable), boundary_during_bind, binding_assessment, signal, proof, universe, applied_cache_universe, applied_f32_bits, lookup, tracking_valid, connected, tracking_result, universe_changed, transform_changed, boundary_signal, raw_pos_f32_bits, raw_q_f32_bits, wire_pos_f32_bits, wire_q_f32_bits, data_source, interval_last_boundary_seq, interval_last_boundary_signal, interval_last_universe_changed, interval_last_transform_changed, interval_last_boundary_signal_observed and coverage_incomplete=1. Interval fields are unavailable when comparison is unavailable or unchanged.

`kind=pose_binding` records a binding immediately before an intended bridge invocation, not send completion, receipt or acceptance. There are no send_begin/end duplicate lines and no timestamp claim. Existing SteamVR log timestamps describe logging only.

Lookup failure retains old applied-cache values and TransformLookupFailed. Nonfinite tx/ty/tz/yaw retains InvalidTransform with the existing outgoing numeric behavior. Neither failure substitutes identity. Existing tracking eligibility, including rotation-only fallback, controls whether Position is sent. When the legacy path sends none, the adapter observes availability only; no binding is fabricated.

Cache refresh cadence, same-ID no-refresh guard, failed-lookup fallback, universe query count, HMD acquisition count, conversion formula, explicit boundary signal mapping, auxiliary-event semantics, event pump/vector handoff, transport reconnect behavior and coverageIncomplete=true remain unchanged. Universe ID, probe owner, transport session, and server Tracker/sourceEpoch/object lifetime remain distinct identities; none becomes a frame identity. Server sample/provenance/prediction contracts are untouched.

## Software verification

The new `monaka_hmd_pose_binding_test` and ctest entry exercise initial/repeated binds, capture ordinal independence, explicit event-before-bind, universe changes before/during binding, same-ID transform changes, simultaneous universe/transform reasons plus an earlier reset, A→B→A, owner mismatch/recreation, unavailable/lookup failure, nonfinite transform components, signed-zero transform comparison, tracking loss without binding, stale/reused/altered markers, reconnect exclusion, immutable held records, exact raw/wire bits and quaternion order, numeric FULL/IMU preservation and exactly-once callback behavior.

Protobuf tests compare serialized Position message bytes before and inside the enabled callback, including special-bit values, and check each component's expected binary32 bits. The overlay script additionally checks original expression/cast identity, all HMD fields, raw diagnostic copy location, unchanged transform arithmetic, non-HMD direct send, every original send call site, single raw/property/search/event call counts, unchanged methods, same-ID guard, vector handoff, bridge/schema/logger hashes, unsupported-client-query absence and enabled/off send reachability.

The concurrency test uses latches and an actual failed try_lock of the adapter's production mutex while a fake send is paused. The event thread then enters the real ObserveEvent path; observer mutation/completion cannot occur until send release. Sender/event threads join before reading results. No sleeps, timing assertions or extra test mutex held across a probe operation are used. The next pose observes the post-send event generation and the held binding remains unchanged. A ctest timeout limits hangs.

Reproduce using `scripts/prepare_direct_driver.py` with the clean pinned source and a new output, then `scripts/test_frame_probe_overlay.py`. Configure a new native build with `SLIMEVR_BUILD_TESTS=OFF`; optional upstream mock-suite is forbidden because it can collide with the production pipe. On this Windows host use Visual Studio 18 2026 x64 and `/DWIN32 /D_WINDOWS /GR /EHsc`, disconnected cached dependency sources, and the hash-checked cached CPM script. Build Release with `/nr:false` forwarded to MSBuild to avoid reusing workers with conflicting Path/PATH environment entries. No old object files are supplied to the fresh build.

Required server command is `gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --rerun-tasks --no-daemon --console=plain`. Sandbox cache/compiler restrictions require an approved unsandboxed retry on this host. Failed launch attempts remain recorded in ignored build logs; they are not labeled PASS.

Final executed verification on 2026-10-06 (Asia/Tokyo):

| Gate | Actual result |
| --- | --- |
| Core | PASS: 681 tests; failures/errors/skips 0 |
| Desktop | PASS: 52 tests; failures/errors/skips 0 |
| shadowJar | PASS with --rerun-tasks |
| mtpProcessE2E | PASS: all 11 scenarios, synthetic separate processes |
| Fresh source/pins | PASS: driver/OpenVR/linalg exact pins above; all three Git status outputs empty after build/tests |
| Overlay preparation | PASS: new pose-binding-final-overlay-2b5d-20261006 and independent pose-binding-repro-overlay-2b5d-20261006 |
| Final reproduction | PASS: built overlay file bytes equal independent fresh preparation after the final trace-field/test refinements |
| Preservation script / existing-output rejection | PASS |
| Fresh configure / Release build | PASS: pose-binding-final-native-2b5d-20261006, Visual Studio 18 x64, /EHsc, /nr:false, optional mock suite OFF |
| Final source refinements | Recompiled frame_probe_test.cpp, VRDriver.cpp and pose_frame_binding_test.cpp in the new build; no previous-phase objects supplied |
| Standalone Direct / frame probe / binding | PASS / PASS / PASS |
| ctest | PASS: 3/3; 0 failed, all three standalone targets only |
| Protected patch | Unchanged/untracked; SHA256 9c04281ea21499a7f5ff89797e7e9620f3d268db5b15fe29e6b67b6a9f33d26d |
| Installed driver / Hardware/HIL | NOT RUN; no deployment or SteamVR restart |

Evidence remains in ignored build artifacts: pose-binding-server-gates-unsandboxed.log; pose-binding-final-native-configure-fresh.log; pose-binding-final-native-build-fresh.log; pose-binding-final-native-rebuild.log; pose-binding-final-ctest.log; and server/desktop/build/mtp-process-e2e/7cb13829-2caf-46ea-b72f-4420c87d2ca8/result.json. Final repro copies were synchronized only after the full build exited, compared byte-for-byte, and their mtimes refreshed to ensure dependency tracking recompiled the final sources. Existing-output preparation still rejects replacement. The initial prototype's incorrect generated-protobuf header include was fixed before the final build; its C1083 failure remains recorded separately, alongside sandbox/tool-launch failures. No failed attempt is reported as a pass. Existing upstream C4715 warnings in protobuf Objective-C helpers and GetRoleName remain outside this change.

## Next phase and limits

Recommended next phase: **Phase 2B-5E — Observed Pose-Binding HIL**, after independent code review. Use short separately enabled binding windows around stationary/moving baselines, seated/standing resets, natural universe changes, same-ID resets, room setup, loss/recovery, transport reconnect and SteamVR restart. Correlate raw/wire bits, applied-cache bits, capture/bind sequences and generations, interval reason snapshots, event lines and downstream captured Position bytes. Investigate marker-query gap, event delivery delay, logger scheduling and cache/live-frame differences separately. Numeric cache-change and lookup-failure coverage remain unobserved from 2B-5C. Exact physical origin, atomic runtime snapshot and complete silent-boundary coverage remain unproven.

| Classification | Scope |
| --- | --- |
| PROVEN BY SOURCE | Capture/bind/send placements, existing conversion/bridge paths, independent opt-in flags, no protobuf/server contract change. |
| UNIT-VERIFIED | Native binding matrix, exact diagnostic/payload checks, deterministic mutex test, preservation script, Direct/probe regressions and server software gates passed. This is not HIL. |
| SOFTWARE-ORDERED | One probe mutex spans final binding through the actual original send invocation and serializes mapped event observation. |
| HIL-UNVERIFIED | Event coverage/delivery, physical frame changes, installed artifact, enabled scheduling/performance and visible SteamVR output equivalence. |
| UNKNOWN | Physical acquisition time, complete live frame identity/continuity, cache/live equality and silent boundaries. |

The raw marker is only a driver-software observation marker, not an acquisition timestamp or frame epoch. Generation equality means no OBSERVED boundary between these software observation points; a changed generation proves that this driver observed a boundary in the interval, not which physical frame applies. Exact diagnostic float samples come from the existing raw conversion locals and the exact outgoing setter floats; raw quaternion binary32 projection is explicitly distinguished above. The bind/send section orders this probe's mapped event observer only, not SteamVR runtime state. No frame lineage is emitted over protobuf; no CoordinateSpace or HMD calibration epoch is created; RawHmdPoseInput stays runtime-blocked. No MainDecoupledHipPredictor or Position Correction path is implemented. MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication, current universe cache semantics, HMD conversion formula and intended visible SteamVR output behavior remain unchanged; physical equivalence is not claimed.
