# Phase 2B-4b: trusted HMD session lineage and current candidates

This phase connects the [Phase 2B-4a transport foundation](position-correction-phase2b4a-transport-session-foundation.md) to the trusted HMD recorder. It adds historical transport lineage and a separate current-session acceptance candidate. It does not create RawHmdPoseInput, a predictor, Position Correction, clock mapping, freshness policy, space/frame proof or solver/output injection. Hardware/HIL: **NOT RUN**.

## Two identities and two kinds of latest

Both `HmdAcceptedPositionSample` and `HmdAcceptedPoseMessageSample` retain `sourceEpoch` for the registered Tracker object lifetime and add `transportSessionEpoch: String?` for the logical connection that received the message. The epoch string is copied from the immutable inbound envelope; no TransportSessionHandle, mutable tracker, active flag or bridge reference is retained in either DTO. Nonnull epochs must be nonblank; null means no transport lineage. Same-object reconnect preserves sourceEpoch and sequence progression but changes transportSessionEpoch. A newly registered Tracker clears both latest holders and restarts its object epoch/sequence; same-object registration remains a no-op.

Existing `acceptedHmdPositionSample()` and `acceptedHmdPoseMessageSample()` remain **historical latest** APIs: the last trusted acceptance processed by the server, possibly from a disconnected connection or delayed older envelope. The position-only record remains insufficient for pose pairing and retains `hmd_pose_pairing_unavailable`.

New `acceptedCurrentSessionHmdPositionSample()` and `acceptedCurrentSessionHmdPoseMessageSample()` return a **current-session acceptance candidate** only. This does not imply finite values, COMPLETE pairing, FULL modality, freshness or known coordinate space. Structural validity and modality remain separate fields/checks. Consumers needing coherent position/orientation read one pose-message DTO; separate position/pose getter calls spanning an acceptance are not a transactional pair.

## Acceptance and publication

The existing server-thread order remains modality assignment, hasX position write, HMD metadata recording, unconditional setRotation, optional velocity, dataTick. At that metadata boundary ProtobufBridge copies only `envelope.transportSession?.epoch` and compares the envelope handle with the current atomic active handle. It passes the epoch and the explicit comparison result to the recorder. The recorder never reads ambient transport state.

Each trusted accepted event allocates one historical sequence and calls the local receipt clock once. Position and pose share sequence, receipt timestamp, object epoch, transport epoch and ingress identity. No separate current sequence or second timestamp is created. Receipt time still means server-thread accepted ingress receipt time, including queue delay; it is not physical acquisition time. No-X, polling, ticks, status and battery do not create either record.

The recorder publishes separate volatile immutable AcceptedMessage holders for historical latest and current-session latest. Historical always advances for trusted acceptance; current advances only for a nonnull epoch explicitly recognized as current at processing. Unexpected blank metadata is rejected without throwing into legacy processing and clears the current slot; normal handles already guarantee nonblank epochs. No locks, source inference or production message reordering are added.

| Envelope / active | Legacy numeric processing | Historical latest | Current latest |
|---|---|---|---|
| B / B | Unchanged | New B sample | New B sample |
| A / B | Unchanged | New historical A sample | Preserve B candidate |
| null / B | Unchanged | Sessionless sample, epoch null | Preserve B candidate |
| A / none | Unchanged | Historical A sample | No update; current read null |

The production queue remains FIFO. A position-boundary fixture also tests a delayed A processed after B: legacy Tracker state and historical latest become A while the current B candidate remains exactly the same immutable object. No stale envelope is dropped and no tracker status is changed by this provenance filter.

## Reader validation and concurrency

Recorder writes remain VRServer-thread confined. Transport active state changes on the bridge thread through the existing AtomicReference lifecycle. Current readers read the active handle, read the separate current holder once and require matching sample epoch, then read the active handle again. A missing active session, missing matching sample or changed handle returns null. Delayed close(A) cannot clear B due to the existing handle compare-and-clear.

An acceptance classified current immediately before disconnect may be stored; reader validation subsequently rejects it. Disconnect preserves history but yields null current reads. Reconnect B before its first accepted HMD message likewise yields null instead of reusing A. The API detects session transitions during its read; it cannot promise that a session will remain connected after a returned value. No global HMD/transport lock or immutable `isCurrentSession` flag is used.

## Platform, trust and readiness

Windows Named Pipe ingress carries enqueue-time logical-session handles. UnixSocketBridge still uses the sessionless overload: history may exist but transportSessionEpoch is null and current-session APIs return null. Sessionless messages never inherit an active handle from another path. The Phase 2B-1 creation registration, SteamVR origin/ID/capabilities and feedback exclusions remain unchanged; a session token alone cannot authorize a raw HMD. External SteamVR HMDs with isComputed=true remain supported.

Pose-message raw rejection reasons include `hmd_session_epoch_unavailable` exactly when transportSessionEpoch is null. A known historical A epoch is not unavailable just because A is inactive; currentness belongs to the reader API. Tagged COMPLETE samples retain `hmd_space_unverified` and `hmd_frame_epoch_unavailable`; partial XYZ, invalid Q and nonfinite position retain all structural reasons. `rawPoseInputEligible` remains false. Transport lineage proves neither CoordinateSpace, tracking universe/recenter/calibration identity nor sample freshness. How object/session identities eventually map into ObservationSampleProvenance.sourceEpoch is deliberately undecided.

## Software coverage and remaining gates

InboundTransportSessionTests covers shared event metadata/one clock, same-object reconnect, disconnect, reconnect before first pose, disconnected backlog, B then delayed A, sessionless acceptance with/without B candidate, subsequent B progression, stale close, no-X rotation/velocity behavior, structural invalidity, object recreation, obsolete/unregistered sources, nonblank metadata and independent Direct lifecycle. Existing HMD acceptance/pairing, trust and Direct capability tests remain enabled. No sleep-based race fixture or hardware test is used.

RawHmdPoseInput remains runtime-blocked. Remaining gates are exact HMD CoordinateSpace; frame/recenter/calibration epoch; runtime clock mapping/freshness; Unix transport-session lineage if required; Main mount-to-HIP_CENTER calibration; temporal pairing with Main; predictor algorithm; Position Correction law; the separate body-model publication versus overlapping legacy-config commit-order coherence gate; and runtime integration.

MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication, HMD numeric processing and visible SteamVR behavior are unchanged. Required software gates are Core/Desktop tests, shadowJar and mtpProcessE2E, including prior stress/diagnostic/contracts/provenance/publication suites. Hardware/HIL: **NOT RUN**.

Software verification for this change used `:server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --rerun-tasks`. Core: 681 tests; Desktop: 52 tests; failures/errors/skips: 0 in both suites. The session suite contains 20 tests (12 added, one existing assertion updated for the new integration). shadowJar and mtpProcessE2E passed. This is software evidence only; no native pipe, HMD or hardware/HIL run is claimed.
