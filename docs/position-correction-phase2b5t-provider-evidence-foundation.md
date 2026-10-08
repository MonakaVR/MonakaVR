# Phase 2B-5T — Authoritative Raw HMD Provider Instrumentation / Contract Foundation

Base: `9a09dd25b4c62a3457d465e7fe0b12c72067a58b`. Work is isolated on
`feature/raw-hmd-provider-authority` in sibling `build/raw-hmd-provider-authority-worktree`.
The local/remote `feature/direct-6dof-output` HEAD, 5S worktree, physical evidence,
reviewed probe candidates, original DLL backup and protected untracked documents
remain unchanged. No SteamVR restart, deployment or physical HIL is part of 5T.
5S remains **BLOCKED — PHYSICAL PROBE DEPLOYMENT / STEAMVR RESTART DEFERRED**.

## Fact ownership and capability

| Fact | 5T owner | Status |
|---|---|---|
| providerSessionEpoch | driver `RawHmdProviderEvidenceState` | AUTHORITATIVE |
| observationId | same independent provider state | AUTHORITATIVE |
| exact raw/wire P/Q | existing driver conversion/final send locals | AUTHORITATIVE SOFTWARE SNAPSHOT |
| sample-bound validity facts | same iteration-local OpenVR pose | AUTHORITATIVE SOFTWARE SNAPSHOT |
| rawSpaceOwner | none | UNAVAILABLE |
| rawSpaceIncarnation | none | UNAVAILABLE |
| rawSpaceGeneration | none | UNAVAILABLE |
| outputSpace descriptor | source-known numeric conventions only | PARTIAL |
| outputSpaceEpoch | none | UNAVAILABLE |
| calibrationEpoch | none | UNAVAILABLE |
| authoritative mappingRevision | none | UNAVAILABLE |
| physical acquisition time | none | UNAVAILABLE |

Raw-space and mapping reasons are `MUTATION_COVERAGE_UNPROVEN`; physical time is
`PHYSICAL_TIME_NOT_PROVIDED`. These are status types with only `Unavailable`,
without invented token/epoch/physical-time values or a caller-authoritative branch.
Source-known mapping describes right-handed +Y up, -Z forward, meters, Hamilton
quaternion xyzw and the existing optional cached universe conversion. It provides
no physical raw-to-output continuity proof or authoritative mapping revision.

OpenVR HMD remains **POSE_ONLY**. Strong Trusted remains **UNSUPPORTED / PARTIAL
PROVIDER FOUNDATION ONLY**. Production RawHmdPoseInput is **BLOCKED BY BACKEND**;
2B-5P is **NOT READY**; production Position Correction is **NOT ENABLED**.
5T PASS does not mean Strong Trusted READY.

## Independent provider lifecycle

`MonakaRawHmdProviderEvidence.hpp` is a pure C++20 value/state contract. It has no
OpenVR, bridge, logger, filesystem or clock dependency. Its factory is injected
for deterministic tests. `MonakaRawHmdProviderDriver.hpp` adds OS entropy and mutex
serialization, without OpenVR queries, logging or evidence transport.

Production creates an opaque 256-bit token using Windows `BCryptGenRandom` with
system-preferred RNG (Linux adapter uses `getrandom`). No timestamp, process ID,
probe owner, universe ID, transport UUID, host sequence or pose hash enters it.
Issued tokens are retained in the state and repeated/blank factory output fails
closed. Across process restarts uniqueness is provided by fresh OS entropy;
there is no persistent deterministic seed or old token reload.

Provider construction starts a session before driver workers. Every bridge
connection explicitly re-establishes a new session before `connected_=true`.
Every `CloseConnectionHandles` synchronously retires before `connected_=false`,
including error, end, reset and stop. Driver cleanup also retires. Only generated
overlay `BridgeClient` receives two local callback slots, configured before Start;
buffer handling, protocol/version, reconnect timer, serialization and callbacks
for existing messages are unchanged. Polling is not used to infer lifecycle,
so a full disconnect/reconnect between pose iterations cannot be missed.

`StartSession` first retires the old session; invalid/repeated token or factory
failure cannot retain it. `RetireSession` clears the current session. Adapter
entropy failures disable evidence while the original send path continues.
State cannot be copied/moved to create two issuers with the same counter/session.
Adapters serialize begin/capture/retirement/re-establishment with their own mutex,
independent of the existing frame probe mutex.

IDs start at **0** and increase once per successful exact sample capture. Poll,
session read, snapshot copy, diagnostics and retransmission never allocate IDs.
`UINT64_MAX` can be issued once, then the session is immediately EXHAUSTED/retired.
No wrap/reuse is possible. The next adapter begin explicitly starts a fresh session
for exhaustion recovery; its first ID is zero. Counter injection is private friend
access in the pure test, with no production force flag/API.

## Exact HMD binding boundary

`scripts/prepare_direct_driver.py` copies the headers/tests into a new overlay of
read-only driver `dcc0f56bcb2a3196d6f92b1ed1d029faa425b931`, OpenVR
`91825305130f446f82054c1ec3d416321ace0072` (linalg
`a3e87da35e32b781a4b6c01cdd5efbe7ae51c737`). Existing-output rejection remains.
Upstream source and submodules are not edited.

The pose thread obtains a session value ticket before the existing raw query.
Before the existing optional universe transform, `hmd_raw_diagnostic` copies
the original `GetPosition`/`GetRotation` locals, using the same binary32 narrowing
as existing probe diagnostics. This raw snapshot is now copied for HMD even when
the frame probe is off. There is no second pose query or quaternion recomputation.

Immediately after `position->set_qw(wire_qw)` and before the unchanged HMD
`BindAndSend`/direct-send branch, one provider capture copies those raw values,
`wire_x/y/z/qx/qy/qz/qw`, `position->data_source()`, and the same `pose.bPoseIsValid`,
`pose.bDeviceIsConnected`, `pose.eTrackingResult`. Capture only applies to HMD
iterations already eligible for an existing Position send. If the lifecycle
changed while obtaining/converting the raw pose, the old ticket fails closed
without allocating an observation under the new session; the original send still
runs. The evidence snapshot remains a local immutable value through the send.

Raw/wire float bits include signed zero, subnormals, finite extremes, NaN payloads
and infinities. Carrier policy is exact preservation: no normalization, identity
substitution, clamping, epsilon comparison or silent zero pose. Source validity
stays three raw facts; there is no synthetic Strong Trusted `sourceValid` boolean.

Snapshot construction is private to state; all members are const value copies.
Callers cannot specify an observation ID or rebuild a different payload with it.
Future retransmission must copy the same snapshot and retain the same ID/P/Q/facts.
Retained snapshots survive state retirement, but cannot authorize new captures.
**Observation captured != server received**: neither the existing send invocation
nor its queuing proves delivery. Lifecycle races may leave no evidence for an
unchanged Position message; evidence is not transported in 5T.

## Probe coexistence and deferred boundaries

`MonakaHmdFrameProbe` remains a separate observed-only diagnostic object;
`detectedBoundaryGeneration` stays coverage-incomplete. Neither probe owner,
probe capture ordinal/sequence, boundary generation, universe ID, SteamVR process
lifetime nor transport identity is projected to provider/raw-space authority.
The provider has no fields for those facts and adds no OpenVR query/event pump.

No provider diagnostic logging is added, so default-off log I/O and formatting
are structurally zero (including with arbitrary env flags). There is no new env
flag, log prefix, JSON/string tunnel or evidence sidecar. A future diagnostic must
say "software provider observation", "not physical acquisition timestamp" and
"not Strong Trusted complete evidence". No physical timestamp is generated here;
raw-query return/call time is not sensor acquisition time.

No protobuf/schema/generated Java/protocol/handshake change is made. Production
`ProtobufBridge.positionReceived` still omits provider evidence (default null).
No server production source, reviewed backend registration, provider session
establishment, RawSourceIdentity projection, RawHmdPoseInput production acceptance
or Strong Trusted/Position Correction activation is added. Exact association to
`steamvr:<bridgeIdentity>:<tracker.name>` requires future wire/integration review.
The C++ carrier corresponds semantically to part of the Kotlin seam, without
claiming its wire ABI or fixing a serialization contract.

## Verification and next phase

Pure `monaka_raw_hmd_provider_evidence_test` covers session/ID ownership, uniqueness,
retirement, reconnect, factory failures, no read advancement, exact float facts,
validity matrix, immutable surface, unsupported authority and overflow.
`monaka_raw_hmd_provider_integration_test` covers independent OS entropy tokens,
retirement without pose polling, in-flight old-ticket rejection, probe coexistence,
actual generated Position serialization with instrumentation/probe on/off, exactly
one callback and status/position/battery order, and zero provider log lines.
Existing Direct, frame probe and pose binding native tests also run through CTest.

`test_frame_probe_overlay.py` uses the exact provider-only allowlist from
`test_raw_hmd_provider_overlay.py`, then checks the preserved upstream methods,
conversion/setter expressions, cache cadence, raw/universe query count, event
handoff, send branches and wire sources. The provider tripwire verifies synchronous
connect/close hooks, independent entropy, unavailable authority, no inferred facts
and every existing tracked server file against the base. Fresh Core/Desktop full,
shadowJar, MTP E2E and the fixed protocol artifact verification run in this worktree.
The local build report records results and Git/preservation receipts.

Next phase: **2B-5U — Provider Evidence Transport / Reviewed Backend Association
Design**. Carry only provider-owned facts bound to exact P/Q, reviewing backward
compatibility, capability negotiation, endpoint association, session establishment
and replay/high-water semantics. If raw-space proof remains unavailable, wire
representation must remain null/Unavailable and Strong Trusted cannot be Ready.
5S physical HIL must independently establish a real raw-space mutation owner
before a separate phase can add that authority. HIL for 5T: NOT REQUIRED / NOT RUN.

## Validation receipt — 2026-10-09 Asia/Tokyo

PASS — PROVIDER-OWNED HMD SAMPLE EVIDENCE FOUNDATION IMPLEMENTED / RAW-SPACE
AUTHORITY STILL BLOCKED. Fresh Release driver DLL and all five native CTest cases
(Direct, frame probe, pose binding, pure provider evidence, provider integration)
pass. Core full: 1,754 tests; Desktop full: 142; zero failures/errors/skips.
shadowJar and all 11 MTP process E2E stages pass. Overlay preservation, provider/
server tripwire, fixed v2 artifact verification and existing-output rejection pass.
5S preservation: all 4,285 protected evidence/artifact/document hashes and original
worktree status/HEAD match the initial snapshot. Detailed local logs, lifecycle,
owner matrix and Git receipts: build/reports/phase2b5t-provider-evidence-foundation-20261009/report.md.
