# Phase 2B-4a: logical inbound transport sessions

This phase establishes transport-session infrastructure and enqueue-time message lineage only. HMD accepted-position and pose-message DTOs do not consume the handle yet; `hmd_session_epoch_unavailable` remains. No HMD filtering, runtime factory, space/frame proof, time mapping/freshness, predictor or Position Correction runtime is added. Hardware/HIL: **NOT RUN**.

## Previous STOP and reusable connections

The Phase 2B-4 audit stopped because `WindowsNamedPipe` allocates a fixed array of `PipeConnection` objects. `disconnect()` returns the same object to CREATED and calls `tryConnect()`. A subsequent `tryAccept()` returns that object again. Object identity cannot distinguish logical accept A from accept B. Phase 2B-4a explicitly separates these lifetimes; the lower-level connection allocation, native handles, disconnect and reuse behavior are unchanged.

The audited production ProtobufBridge subclasses are WindowsNamedPipeBridge and UnixSocketBridge via SteamVRBridge. Both call `messageReceived`; the latter remains session-less in this phase. HMD creation registration and tracker reuse remain unchanged, including `trackerAddedReceived()` returning early for an existing ID.

## Logical handle and accept ordering

`TransportSessionHandle` is an immutable nonblank opaque UUID identity. `ProtobufBridge.openInboundTransportSession()` generates a new handle for each call and installs it in an AtomicReference. It is independent of PipeConnection identity, Tracker identity, HMD receipt clock and Direct capability tokens.

WindowsNamedPipeBridge uses this sequence on the bridge thread:

```text
tryAccept() succeeds
  -> openInboundTransportSession(): create/install handle
  -> save connection's logical handle in bridge-local state
  -> queueTask(reconnected) (existing output lifecycle)
  -> connection.update(this) in OPEN state
  -> onMessage(connection, buffer)
  -> copy saved handle, parse, enqueue(message, handle)
```

`tryAccept()` only invokes `update(null)` for CREATED connections. The successful CREATED case sets OPEN and returns immediately; it does not fall through to the OPEN case or call `tryRead(reader)`. Thus the normal first message callback cannot precede handle installation. `onMessage` remains synchronous within the bridge thread's OPEN update and validates the current connection and a nonnull logical handle. This ordering is code-audited; native Named Pipe connections are not exercised by the unit tests.

## Active session and close

Active session means the current logical accept lifetime; it does not identify the origin of already queued messages. `currentInboundTransportSession()` performs one atomic read. `closeInboundTransportSession(handle)` uses compare-and-set with that exact immutable handle. Delayed close(A) cannot clear active B, including when the native connection object is the same.

Windows closes its saved handle before the existing ERROR disconnect/reuse operation, then keeps the existing `disconnected()` behavior. It also closes the saved handle in `run()` finally before closing the pipe. IOException, interruption and normal runner exit all pass through finally. `stopBridge()` keeps its existing thread interruption behavior; once the runner exits, active state is cleared. This is not a promise of synchronous native shutdown at the instant stop is requested. Parse failures retain existing `setError()` behavior, enqueue no message and close the session through ERROR cleanup; they do not generate another handle. Cleanup is idempotent and does not depend on connection object identity.

Existing `reconnected()` and `disconnected()` callbacks retain their Direct/tracker side effects and do not create or close transport handles. Windows queues reconnected on VRServer; its existing error-path disconnected call is on the bridge thread despite the inherited VRServerThread annotation. Unix queues both callbacks. This phase does not change that pre-existing scheduling or move tracking writes between threads.

## Immutable inbound envelope

`InboundProtobufEnvelope` stores an immutable protobuf message and nullable TransportSessionHandle. The Windows callback copies the saved accept handle before parsing, and calls the session-aware `messageReceived(message, handle)`. The existing one-argument overload enqueues null for Unix/test/non-session callers, even if another handle is currently active. No default session is invented.

The queue remains a LinkedBlockingQueue with the same FIFO add/poll behavior. `dataRead()` forwards `envelope.message` and `envelope.transportSession` to `processMessageReceived`, then to `positionReceived` for Position messages. It never reads current active session to annotate a message. The protected processing boundaries can be observed in tests while delegating to the unchanged legacy handlers. Phase 2B-4a does not pass this context into the HMD recorder.

```text
receive A1 -> envelope(A1, A)
receive A2 -> envelope(A2, A)
close A; accept B
receive B1 -> envelope(B1, B)
drain -> (A1, A), (A2, A), (B1, B)
```

There is no old-session message drop, coalescing or retagging. Historical A messages may still change legacy Tracker numeric state after B becomes active or while disconnected, just as before. Current-session HMD provenance filtering is intentionally Phase 2B-4b; infrastructure alone does not make these historical values trusted current HMD inputs.

## Direct output and tracking compatibility

Direct capability token generation stays inside the existing VRServer-side reconnected callback. Its token validation, Direct queue purge, announcement and output support gate are unchanged. A Direct callback can run after the first inbound message has been queued without changing that message's transport handle. Transport open/close does not invoke capability negotiation.

Only inbound metadata packaging and context propagation change. Message count/order, position/rotation/velocity/modality/status/dataTick writes, orientation sequence, shared trackers and output queue semantics remain unchanged. No protobuf/driver wire fields are added. Transport lineage does not establish HMD trust, CoordinateSpace, universe/recenter/calibration identity or freshness.

## Tests and remaining gates

`InboundTransportSessionTests` uses a fixed HMD receipt clock and serialized messages through the real ProtobufBridge queue. It covers distinct handles across repeated logical accepts, matching/duplicate/stale close, A backlog processed with A after B opens, FIFO A/A/B, position-boundary context and unchanged numeric writes, disconnected backlog, null-session compatibility, all message types without implicit session changes, independent Direct capability negotiation and unchanged HMD DTO/object epochs/readiness. Existing HMD and Direct capability regressions remain enabled. No sleeps, native test connection or fake production trust path is introduced.

Status:

| Boundary | Status |
|---|---|
| Windows logical transport-session infrastructure | ESTABLISHED |
| Windows enqueue-time session lineage to position processing | ESTABLISHED |
| Unix transport-session infrastructure | NOT IMPLEMENTED; null context preserved |
| HMD accepted sample session integration/current filtering | NOT IMPLEMENTED (Phase 2B-4b) |
| Exact HMD CoordinateSpace/frame/recenter/calibration identity | BLOCKED |
| Runtime clock mapping/freshness | NOT IMPLEMENTED |
| RawHmdPoseInput runtime-ready | NO |

Other Position blockers remain Main mount-to-HIP_CENTER calibration, Main temporal pairing, predictor algorithm, correction law and runtime integration. The body-model publication versus overlapping legacy-config write-order coherence gate remains separate and unchanged. Core/Desktop, shadowJar and MTP process E2E are the required software gates. Hardware/HIL: **NOT RUN**.
