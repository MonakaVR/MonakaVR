# Phase 2B-5AB: dormant common-world ordered receiver

`TrustedHmdCommonWorldStream` is an internal Core-only state. It is never instantiated by MonakaRuntime,
MonakaSolverComposition, desktop transport or a production backend. The caller serializes mutations and
explicitly selects the expected Bridge publisher/session/clock. Decoding cannot authorize a publisher.
There is no socket, listener, worker thread, RawHmdPoseInput creation or Reviewed registration.

## Exact offline dependency

Protocol source: `ea1e02c15c20693070ce69cfbc54941ee7ae8e7d`.

| Receipt | SHA256 |
|---|---|
| monaka-protocol-kit-v2.1.zip | 8d9ba5087cf9935d7c2432962086d305b34e71d805838bd96ffbef9eef90413d |
| handoff-manifest.json | 9d71b722da0e5a6f72d82cc2373069d570e59c195a43b58e539b29827caa14b8 |
| protocol.lock.json | e196692dbe3c533fdd93e18e73c24a660cbefc88780802b10119c1dfc3398e55 |

The new pin/artifact slot is `dependencies/monaka-protocol-v2.1.lock.json` / `dependencies/protocol-v2.1`.
Historical v2.0 pin/artifact bytes remain in their original paths. `verifyMonakaUpstream` invokes the
read-only `scripts/verify_protocol_v21.py`; `verify_protocol_v2.py` independently checks the historical
receipt. Verification covers CRC, unique safe paths, full SHA256SUMS, source/wire/contracts/lock hashes,
every materialized member and no stale members. The current third-party tree is the exact 298-file kit,
including the supplied JVM JAR; no consumer-side codec/JAR regeneration occurs.

## Stream lifecycle and isolation

Only WorldAuthorityPublication, MappingPublication, CommonPose, CommonUnavailable, MappingRevocation and
WorldRevocation enter this state. All four source messages and legacy MTP/Observation messages reject
without mutation. MtpInbox remains restricted to its two original MTP types; tests send authority bytes
to it and verify that the inbox remains empty.

Fresh authorized sessions require seq0 world and seq1 mapping. A rejected/missing seq1 cannot be repaired
with a later mapping. A healthy new session clears visible state/candidate until its full snapshot is
accepted while retaining the same long-lived `CommonWorldAuthorityState`. Exact current nonrevoked mapping
snapshots can bootstrap again; stale or same-revision changed snapshots cannot. Publisher session is not
source/world/calibration identity or transport authorization.

Events are contiguous across all six types. Typed semantic equality compares every known field except
resend-mutable `sent_at_ns`; unknown optional fields are discarded by the codec. Caller-owned lists are
copied before retaining immutable history. Exact duplicates and lower sequence ignore without receipt
refresh; lower packets with changed clock/content cannot invalidate current trust. Gap, same-sequence
conflict, current-clock mutation, malformed authorized bytes and explicit transport disconnect immediately
clear the candidate and locally revoke the world with EVENT_STREAM_LOST. Same-session repair is impossible;
fresh session plus fresh nonretired world/calibration snapshot is required after loss. No wire revocation
is fabricated. INT64_MAX never wraps to a new accepted event.

Publisher session retention, source-token history and the foundation's world/calibration/revision bounds
default to **128** each. No tombstone is evicted; saturation permanently closes trust. Histories are
in-memory for this receiver lifetime; persistent replay protection after process restart remains future work.

## Exact wire to 5Z projection and numerical policy

Source ID/token/space/generation map to SourceSpaceAuthorityEpoch. Owner/CoordinateSpace/world epoch/anchor
map to CommonWorldAuthority. Calibration/mapping revision map to their independent 5Z types. Rotation
wire xyzw becomes Quaternion(w,x,y,z), and translation becomes Vector3. Source locate-time/domain and
all six validity facts remain separately attached to the structural candidate; locate time is never
copied into a local sample clock.

All Double components must be finite, and conversion must produce finite Float values without overflow.
5Z independently checks its stricter quaternion norm-squared contract after conversion; no normalization,
clamping or repair occurs. Pose acceptance constructs the exact source snapshot, recomputes P=R*P+t and
Q=Q_mapping*Q_source through the current immutable 5Z mapping, then compares raw Float component bits
against Float-converted wire common P/Q. No arbitrary epsilon or q/-q equivalence is used. This is a
software-only Float-domain consumer policy; a future Bridge publisher must prove this numerical contract
before live wiring, including nonidentity transformations. Binary64 immutable mapping history separately
detects equal-revision changes even when different Double values round to identical Float values.

Observation high-water is source-token-local and survives generation/world/publisher changes. Source
identity/time-domain/P/Q/evidence conflicts fail closed. Reusing an observation for a new mapping preserves
its original local receipt; duplicates and unavailable do not refresh age. Unavailable requires a new
observation to recover the same mapping. Results are structural values, never production HMD ingress.

## Ordered world/mapping and preserved 5Z behavior

Same-world higher mapping revisions clear the candidate before new poses; every pose requires the exact
world/CoordinateSpace/source/calibration/revision. Mapping revocation immediately clears mapping and pose
while retaining world. World revocation clears all three without a following pose. World replacement must
be ordered after old-world revocation, then new world, mapping, pose. Retired world/calibration/source tokens,
generation/revision/observation high-waters cannot ABA back into trust.

C2.1 explicitly scopes mapping revision history to owner/world epoch; exact kit `common-world-replace`
uses revision 7 in both world-A and world-B. The foundation gains an opt-in `worldScopedMappingHighWater`
mode used only here: a genuinely fresh nonretired epoch resets that epoch's mapping high-water; repeating
the current epoch never resets it. Prior epochs remain permanently retired. Existing 5Z callers retain
their Config-global revision behavior, and the original 50 foundation tests remain unchanged. A world-level
`anchor_authority_lost` alone retires the world/mapping, not an inferred source generation: the exact 5AA
fixture replaces the world with the same anchor. Explicit source-space/session change retains the stronger
generation/token retirement. The receiver does not invent a source revocation message.

## Validation and production status

Exact kit common ordered JSONL scenarios run bytes -> supplied codec -> receiver, including seq102 world
revocation at EOF, same-world updates, replacement, reconnect, high-water, retired world and stale mapping.
Additional tests cover authorization, duplicates/conflicts/gaps, transport loss, Float overflow/unit norm,
nonidentity raw-bit math, wrong protocols, source observation conflicts, list mutation and retention bounds.
Core/Desktop full, shadowJar and MTP process E2E 11 stages validate existing v2.0 paths.

Generic OpenVR and Strong Trusted remain unsupported; Reviewed backend is not established;
RawHmdPoseInput production is blocked; Position Correction is not enabled; 2B-5P is not ready.
ALVR sender, Bridge world owner/common publisher and live authority transport remain unimplemented.
No 5S probe deployment, installed driver/config change or physical HIL occurs.

Next phase: **Phase 2B-5AC — MonakaBridge Common World Runtime Owner / Dormant Publisher Foundation**.
