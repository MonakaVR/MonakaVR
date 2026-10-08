# Phase 2B-5U — Provider Evidence Transport / Reviewed Sample Association

Base HEAD: `164a2d86bfdd0ea29fa269e69fa9825c0707e3fb` (5T).
Branch: `feature/raw-hmd-provider-transport`; isolated sibling worktree:
`build/raw-hmd-provider-transport-worktree`.

This phase carries only the software sample facts actually owned by the 5T
provider. Negotiated transport readiness does not complete Strong Trusted.
Raw-space authority and mapping epoch authority remain **BLOCKED**; full
`ReviewedHmdBackendContract` is **NOT ESTABLISHED**; Strong Trusted remains
**UNSUPPORTED / PARTIAL PROVIDER TRANSPORT ONLY**. `RawHmdPoseInput` stays
**BLOCKED BY BACKEND**, production Position Correction is **NOT ENABLED**,
and 2B-5P is **NOT READY**. No complete predictor activation occurs.

## Wire v1 and schema authority

`Position.hmd_provider_evidence_v1` is message field **13**. Existing Position
fields 1–12 retain their names, types, presence and tags. `ProtobufMessage`
oneof tags 1–6 and protocol version 2 are unchanged.

| Evidence field | Tag | Wire type / presence |
|---|---:|---|
| provider_session_epoch | 1 | optional string |
| observation_id | 2 | optional uint64, accepted domain 0..INT64_MAX |
| raw_x / raw_y / raw_z | 3–5 | optional float |
| raw_qx / raw_qy / raw_qz / raw_qw | 6–9 | optional float |
| wire_x / wire_y / wire_z | 10–12 | optional float |
| wire_qx / wire_qy / wire_qz / wire_qw | 13–16 | optional float |
| data_source | 17 | optional int32 |
| pose_valid | 18 | optional bool |
| device_connected | 19 | optional bool |
| tracking_result | 20 | optional int32 |

All 20 fields require presence, including explicit false/zero facts. Wire v1 has
**no fields or placeholders** for rawSpaceOwner/incarnation/generation,
outputSpaceEpoch, calibrationEpoch, mappingRevision, physical acquisition time,
source identity or sourceValid. Unknown protobuf bytes cannot create those facts
in the server's DTO or projection.

The sole schema patch authority is
`scripts/monaka_bridge_protocol_overlay.py`. It validates the SHA256 of the
LF-normalized complete original schema, then adds exactly the v1 message and
Position field 13. The original source is
`SlimeVR/SlimeVR-OpenVR-Driver`, commit
`dcc0f56bcb2a3196d6f92b1ed1d029faa425b931`,
`src/bridge/ProtobufMessages.proto`. Its normalized SHA256 is
`0ce72ac1dd484c3d3c4d93f350d55a4880b6c74c29a3028a580bf77682b71c59`.
Already-patched, altered or foreign input is rejected. CRLF and LF checkouts
produce identical patched UTF-8/LF bytes.

`prepare_direct_driver.py` and `generate_monaka_protobuf.py` invoke this same
helper. No duplicated schema patch and no independently edited canonical proto
exist. Generation requires a clean pinned checkout and exactly **libprotoc 31.1**;
version mismatch fails before generating. The server Java is protoc's untouched
4.31.1 output. `.gitattributes` preserves its bytes on Windows, and Spotless
excludes it. Protoc's own whitespace is preserved for exact reproduction.
`server/desktop/protobuf_update.bat` is a thin generator wrapper.

Reproduce from the repository root (paths below are explicit inputs):

```text
python scripts/generate_monaka_protobuf.py --source PINNED_DRIVER_CHECKOUT --protoc PROTOC_31_1 --output build/protocol-reproduction --check server/desktop/src/main/java/dev/slimevr/desktop/platform/ProtobufMessages.java
python scripts/prepare_direct_driver.py --source PINNED_DRIVER_CHECKOUT --output NEW_OVERLAY_DIRECTORY
python scripts/test_monaka_bridge_protocol_overlay.py --source PINNED_DRIVER_CHECKOUT --protoc PROTOC_31_1 --protobuf-jar PROTOBUF_JAVA_4_31_1 --output build/protocol-compatibility --overlay NEW_OVERLAY_DIRECTORY
```

The protocol test generates both current and pinned-original Java parsers in
ignored build directories, compiles them separately, and verifies exact old
outer fields, unknown field 13 handling, round-trip bytes, three absent-evidence
goldens, a fixed evidence-present golden, and Java/schema byte equality.
No old generated parser is tracked. The evidence golden includes INT64_MAX,
signed zero and a raw NaN payload.

## Provider ownership and observation domain

5T's `RawHmdProviderEvidenceState` remains the sole ID issuer. Its C++ counter
storage stays uint64_t for the existing carrier ABI, but issuance now stops at
`std::numeric_limits<int64_t>::max()`. IDs are 0..9223372036854775807, matching
nonnegative Kotlin Long. The upper sample is issued once, then the session is
immediately retired/EXHAUSTED. Another capture cannot wrap or reuse the ID.
The next driver BeginSample must establish a fresh provider session; entropy
failure leaves evidence unavailable while the original Position send continues.
Factory reuse/blank output and stale in-flight tickets still fail closed.

Capture remains immediately after the final Position P/Q setters. Raw values
come from the existing pre-universe conversion locals, final wire values from
the existing send locals, and validity from the same iteration-local OpenVR
pose. No additional raw query, numeric conversion, normalization, identity/zero
replacement, logger or acquisition clock is added. Capture can continue before
negotiation, so the first transported ID may be greater than zero; gaps are legal.

Evidence is attached to that same Position object, before the unchanged
probe/direct send branches. No UserAction pose sidecar, separate sample message,
queue read or new Position send is introduced. Non-HMD, non-FULL, capture
failure and server-to-driver Direct output carry no provider evidence.

## Capability and current transport association

Every server inbound transport accept creates a separate fresh opaque challenge
and sends `monaka-hmd-provider-evidence-v1?` with `connection=CHALLENGE` in the
existing UserAction arguments. The driver exact-echoes the arguments and replies
with `monaka-hmd-provider-evidence-v1`. Its atomic enabled flag is initially
false and synchronously resets on connect, disconnect and cleanup. Only a query
with a nonempty token enables evidence; Direct queries never enable it.

The server owns `REVIEWED_HMD_SAMPLE_TRANSPORT_V1`, a
`ReviewedHmdSampleTransportContract` containing a fixed contract ID, reviewed
source reference and evidence version. The response confirms capability only.
Remote strings cannot construct or register any backend contract. This partial
review is expressly separate from `ReviewedHmdBackendContract`.

Replies must carry both the exact current challenge and the exact current inbound
TransportSessionHandle. Wrong, stale, sessionless or foreign replies are ignored;
duplicate confirmation is idempotent. Close/reaccept clears association and
replaces the challenge. A late close for an older handle cannot clear a newer
association. Evidence decode requires current handle and association epoch;
current diagnostic reads also check association and sample epoch together.

The existing Direct token/state and handshake are retained independently.
HMD and Direct challenges are different; neither can confirm the other capability.
Negotiation sends queries but does not alter generic Position processing.

| Identity | Owner / meaning |
|---|---|
| providerSessionEpoch | provider OS-entropy sample issuance lifetime |
| observationId | provider session-local ordered software observation |
| TransportSessionHandle.epoch | server inbound transport lifetime |
| challenge | server capability confirmation nonce for that accept |
| ingressIdentity.sourceId | server registered HMD creation identity |
| sourceEpoch / sequence | host object lifetime / accepted-message counter |
| receivedAtSystemNanos | host decoded-message ingress receipt, not acquisition |

No identity is substituted for another, or for raw-space or mapping authority.
Negotiation is capability association with a reviewed schema/sample contract;
it does not authenticate physical hardware or prove raw-space continuity.
Capture/send does not guarantee delivery. Existing in-flight transport queues
remain unchanged, and current-session read gates fail closed after transitions.

## Server decode and partial projection

Evidence must be on tracker ID 0, with outer x/y/z/data_source present and FULL.
All nested fields must be present; provider session must be nonblank; the Java
uint64 getter must be nonnegative (rejecting values above INT64_MAX). Nested
data_source must equal outer data_source exactly.

All seven nested wire P/Q fields must equal their corresponding outer fields
using `Float.floatToRawIntBits`. Signed zero, one-bit differences and distinct
NaN payloads are distinguished. Raw P/Q is preserved even when nonfinite;
numeric equality/epsilon is never used for binding. Quiet NaN payload preservation
has been tested on this Windows/MSVC/JDK17 toolchain, without claiming other
platforms or signaling-NaN behavior.

Malformed, absent or unconfirmed evidence is discarded independently of the
original hasX Position update path. Tracker position, rotation, status, velocity,
modality and dataTick retain their existing behavior. A rotation-only/no-X message
also invalidates the current transport-evidence diagnostic without manufacturing
a new accepted Position or changing historical numeric acceptance.

The accepted same-message pose stores `HmdProviderSampleTransportEvidenceV1`.
Its sole one-way partial projection uses the **server registered**
`steamvr:<bridgeIdentity>:<tracker.name>` source ID. Provider session/ID are
projected; raw-space, mapping and sourceValid are null. poseValid,
deviceConnected and trackingResult remain separate raw DTO facts. No reviewed
validity policy is synthesized.

Production does not call `establishProviderSession`, construct a full reviewed
backend, activate Strong observation high-water, or supply providerPolicy.
`providerSession` and `trustedCandidate` stay null. The production
`rawHmdPoseInputCapability()` remains Unavailable. Partial transport diagnostic
methods cannot return a Ready capability or enable Position Correction.

## Compatibility, verification and 5S separation

| Server / driver | Behavior |
|---|---|
| old / old | original Position behavior and fixed golden bytes |
| old / new | no query; evidence disabled; pre-5U Position bytes exact |
| new / old | query ignored; no association; generic HMD updates exact |
| new / new | evidence only after query; current confirmed transport accepts partial facts |

Tests include native lifecycle/exhaustion/handshake/bit copies/reconnect/generic
fallback, all old native regressions, server malformed-field and bit-binding
cases, current/stale/foreign challenge isolation, server-derived source identity,
partial projection and blocked admission. Full Core/Desktop, shadowJar and
11-stage MTP process E2E must pass before commit/push. Preservation scripts verify
the original conversion, cache cadence, query count, send branches, transport
buffer implementation and unrelated server/admission files.

The 5S worktree at `9a09dd25b4c62a3457d465e7fe0b12c72067a58b`, its report,
evidence manifest, original DLL backup, reviewed probe DLL and dry-run deployment
documents are protected independently. They are not build outputs or modification
targets for 5U. Hash/status/HEAD receipts belong to the ignored 5U report directory:
`build/reports/phase2b5u-provider-evidence-transport-20261009/report.md`.

5S remains **BLOCKED — PHYSICAL PROBE DEPLOYMENT / STEAMVR RESTART DEFERRED**.
5U HIL is **NOT REQUIRED / NOT RUN**. No DLL installation, SteamVR restart or
physical validation is performed. The remaining hard blockers are raw-space and
mapping authority. Resume 5S independently when available; 5V contract design is
a separate future task, not token/authority implementation in this phase.

## Validation receipt — 2026-10-09 Asia/Tokyo

Fresh Release DLL and native CTest: **6/6 PASS**. Full Core: **1,754 PASS**;
full Desktop: **150 PASS**, with zero failures/errors/skips. HMD transport: 8;
RawHmd admission: 49; Strong Trusted policy: 30; HMD provenance: 16;
Direct protobuf: 3; inbound transport/session regressions: 20.
shadowJar: **PASS**; MTP process E2E: **11 stages PASS**.
Fresh libprotoc 31.1 schema/Java reproduction, fresh pinned old parser, fixed
absent/present goldens, quiet NaN payload bits, overlay preservation and server
admission tripwires: **PASS**. Native execution is Windows/MSVC; server execution
is JDK17. Linux/Apple and physical hardware execution: **NOT RUN**.
Git completion and 5S preservation receipts are recorded in the local report.
