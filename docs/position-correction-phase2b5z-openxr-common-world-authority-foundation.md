# Phase 2B-5Z — OpenXR-Anchored Common World Authority Foundation

2026-10-09. Base: `744ab4c08f8426d59afacb459314134d7b816b74`.

## Scope

Dormant typed foundation in MonakaVR core and legacy desktop admission hardening. MonakaVR receives externally published authority; it does not allocate/mint world tokens, solve mappings or own Bridge persistence. No ALVR/OpenXR integration, network transport, protobuf/schema/codec changes, reviewed backend registration, Strong Trusted activation, production RawHmdPoseInput producer, Position Correction activation, HIL or deployment.

## 5Y contract reference

[Exact-copy 5Y contract](position-correction-phase2b5y-alvr-openxr-monaka-mapping-authority.md), M2/B1 only: ALVR/OpenXR source authority → Bridge Common World/mapping/calibration owner → MonakaVR consumer. Read-only source/report hashes, ALVR original/prototype hash checks and baseline findings are in `build/reports/phase2b5z-openxr-common-world-authority-foundation-20261009` of the parent workspace repository. Source copy and destination hashes must match. The fixed ALVR prototype is `e94ff1967c70da480531c3ec9f6956d3be8b5ce5`, upstream `e0d83b46168449cb0bb514770dd926b0ed85c55a`.

No source-proven 5Y design conflict was found. The explicitly requested same-calibration/newer-revision structural update is representable here; future Bridge fit/reacquisition policy still owns issuing fresh calibration epochs when that binding lifetime changes.

## Type model

`CommonWorldAuthority.kt` is independent of desktop, OpenXR handles, ALVR types, Tracker/solver/output types and protobuf.

| Type | Identity / value |
|---|---|
| SourceSpaceAuthorityEpoch | source ID, source-owned XrSession authority token, source-space semantic ID, nonnegative combined STAGE/VIEW generation |
| CommonWorldEpoch | nonblank opaque token supplied by future Bridge; never derived from clocks, source/transport session, revision or pose |
| CommonWorldAuthority | explicit owner ID, CoordinateSpace (destination frame/worldRevision), opaque epoch, source anchor |
| CommonMappingCalibrationEpoch | nonblank source→common binding-lifetime token, separate from source-native calibration |
| CommonMappingRevision | mandatory Bridge Config uint32 content revision, independent of worldRevision |
| SourceToCommonRigidTransform | immutable ktmath unit quaternion and finite translation, direction common-from-source |
| SourceToCommonMappingSnapshot | owner, exact source space, full world value, calibration epoch, revision and transform |
| Live world/mapping handles | reference identity; internal constructors, no data-class copy; equal forged values are noncurrent |
| CommonWorldAuthorityRevocation | owner/world epoch/reason and local diagnostic sequence; no wire serialization or identity substitution |

The receiver requires one explicitly selected owner. The source-space ID must identify the reviewed VIEW-in-STAGE/identity-offset/origin semantics in a future registered contract. This structural type does not establish those proofs, six validity facts, source liveness, clock correspondence or ingress trust. Transport session identity remains outside these authority types.

Both destination and mapping revisions use 0..UINT32_MAX, matching 5Y/Bridge semantics. Generation and observation ID use nonnegative nonwrapping Long values, matching 5X's INT64_MAX ceiling. Unit-norm-squared tolerance `1e-5` is audited from Bridge `src/mapping/config.cpp`. No input normalization, identity fallback or clamping occurs.

## World lifecycle

`CommonWorldAuthorityState` accepts externally supplied values. First publication establishes a handle; exact republication returns the same handle. Same token with changed owner/anchor/space closes world and mapping authority. Unexpected owner publication also closes live authority. Replacement needs a fresh opaque token and a strictly higher worldRevision in the same space ID namespace. Different space IDs have separate bounded revision high-waters; token retirement remains global to this receiver lifetime.

Retired tokens cannot return after replacement/revocation, even under a different space ID or larger revision. World revocation immediately invalidates both handles and retires the active mapping calibration. Tombstones/high-waters are never evicted or cleared. Default bound is 128 retired world tokens, 128 retired calibration tokens and 128 space revision namespaces; saturation permanently closes this state. Rejected stale world publication preserves current good authority. A future runtime must own the receiver lifetime/restart/persistent revision checks; this phase does not implement persistent allocation.

## Mapping lifecycle

Publication requires the exact current world and owner; before-world, foreign-world and stale snapshots are rejected. Identical live snapshots are idempotent. Equal revision with different content revokes mapping authority; lower revision rejects without destroying the good current mapping. Global Config revision high-water survives world replacement and mapping revocation. Republish after withdrawal requires a newer revision and fresh calibration epoch; equal old content cannot revive a withdrawn handle.

Calibration C1→C2 retires C1; C2→C1 is rejected even with a larger revision/identical transform. Retirement survives world replacement and revocation. Source-space change within a live mapping revokes it and requires fresh publication. This is a single-source mapping state; future registration/coordinator owns per-source state selection.

## Same-world mapping update

`W1/worldRevision7/C1/mapRev100/T1 → W1/worldRevision7/C1 or C2/mapRev101/T2` is legal for the same source space. World handle stays identical; mapping handle is replaced. Old handles become noncurrent immediately. The authoritative snapshot always has a revision, even for identity transforms; legacy nullable identity projection is separate.

## Revocation semantics

Typed reasons cover anchor/source-space/session loss, event stream loss, explicit replacement, mapping conflict/withdrawal and capacity exhaustion. Receiver-local revoke requires no subsequent pose. Mapping-only revoke preserves world; world revoke invalidates both. Operations and final acceptance synchronize on the receiver monitor, giving a local linearization point. Diagnostic sequence is not a world incarnation token. Source loss must eventually arrive through an ordered future transport; 5X does not currently provide that channel.

## Exact in-flight binding

`mapSourcePose` captures one live mapping handle, verifies exact source identity, computes with that immutable snapshot, then checks `currentMapping() === before` and world handle identity under the mutation monitor before allocating the result. Update/revoke/replacement during computation returns null. No getter adds newer provenance to an older result.

The result retains the complete source observation ID/space/session/generation/P/Q and mapping/world/epoch/worldRevision/calibration/revision/transform. It is a structural value, not a Strong Ready factory or RawHmdPoseInput producer. HMD M2 strict factory requires exact world anchor and derived numerical identity transform; generic snapshots represent nonidentity mappings.

`P_common = R_mapping * P_source + t_mapping`; `Q_common = Q_mapping * Q_source` uses the audited ktmath Hamilton product/sandwich. Positive identity preserves source bits including signed zero. Negative identity rotation is numerically identity but still negates output quaternion through the specified product; source bits remain unchanged in the source snapshot. Nonfinite/overflow results reject. Callback-based race tests have no sleeps.

## CommonWorldEpoch provenance

Backward-compatible trailing `commonWorldEpoch: String? = null` fields were added to ObservationSampleProvenance, PositionPredictionEpoch and PositionTeacherEpoch. Non-null values must be nonblank. Prediction input requires exact HMD/IMU equality; extraction copies the HMD's exact token after that check. Teacher extraction copies teacher provenance. Structural eligibility requires teacher/prediction exact world equality with `common_world_epoch_mismatch` on mismatch. Error/pairing facts retain the token through their existing epoch objects; no duplicate top-level field was added.

Dormant orchestrator assembly adds only an unavailable mismatch guard before constructing the input, preventing a constructor exception for partial provenance. Non-HMD teacher context additionally rejects an injected token unsupported by the current MTP context. Temporal limits, prediction math, learning/application and production wiring are unchanged.

## Legacy null semantics

| HMD / IMU or teacher / prediction | Result |
|---|---|
| null / null | existing legacy behavior |
| W1 / W1 | coherent structural provenance |
| W1 / null, null / W1 | reject |
| W1 / W2, even in identical CoordinateSpace | reject |

Neither null nor numeric revision is upgraded to live authority. Sample progression leaves world identity unchanged; a changed token changes prediction/teacher continuity identity.

## MTP gap

Current wire/fixtures/codec and generated protobuf Java are unchanged. MTP provenance remains null because no wire field supplies a CommonWorldEpoch. `calibrationEpoch=input.session_id` remains native input lineage, not a world fit token. Future trusted HMD W1 + current MTP null cannot form an eligible teacher pair; future HMD W1 + current configured IMU null cannot assemble a predictor input. This prevents partial activation.

## Raw IMU gap

SlimeRawImuCoordinateSpaceBinding has an optional runtime-only token copied to sample provenance. It leaves physical observation ID/sample time/source/native calibration intact. PositionCorrectionRawImuSpaceConfig and JSON persistence have no live token field; `toBinding()` supplies null. Approved common relation publication, exact native/common binding proofs, coordinated invalidation and operator reconfirmation remain future work.

## Legacy Raw HMD hardening

HmdProviderObservationState now allows changed mapping within identical outputSpace/outputSpaceEpoch only when both revisions are non-null and strictly increasing. It clears the candidate and retains observation high-water/last observation. A new physical observation with current mapping evidence is required; older/replayed candidates are never remapped. Exact mapping remains idempotent. Same revision conflict, mutable nullable revision and changed output descriptor in the same epoch retire; lower revision rejects and clears candidate without replacing the current good context.

Retired calibration and output epoch tombstones survive provider session changes; raw-space generation history remains provider scoped. Capacity exhaustion closes trust without eviction. Fresh output incarnation follows existing generic legacy semantics, without imposing CommonWorld-specific persistent worldRevision policy on that path. Generic OpenVR retains Unavailable with FRAME_REFERENCE_UNAVAILABLE, PROVIDER_SESSION_UNAVAILABLE, OBSERVATION_ID_UNAVAILABLE and RAW_SPACE_GENERATION_UNAVAILABLE.

## Production gates

| Gate | Status after 5Z |
|---|---|
| ALVR source authority | PROTOTYPED (unchanged 5X) |
| Common World foundation | READY, structural and dormant |
| Runtime Common World owner | NOT IMPLEMENTED |
| Transport | NOT IMPLEMENTED |
| Full Reviewed backend | NOT ESTABLISHED |
| Strong Trusted | UNSUPPORTED |
| RawHmdPoseInput production | BLOCKED |
| Position Correction | NOT ENABLED |
| 2B-5P | NOT READY |
| HIL / SteamVR restart / driver install / APK / ADB / deployment | NOT RUN |

## Validation and preservation

Focused lifecycle/race/math/provenance and existing Raw HMD/policy/transport/prediction/pairing/IMU/adapter tests, Core/Desktop full tests, shadowJar and MTP 11-stage separate-process E2E are required. Actual final counts/results, attempts, command logs, source hashes and before/after preservation receipts are in the final workspace report; no prior PASS is carried forward. Existing tests are not deleted/ignored. The former same-output/newer-revision rejection test is replaced with positive update/high-water coverage plus conflict/null/rollback/ABA rejection tests.

Final source validation: focused Core **242**, Desktop **90** (Common World **50**); Core full **1,810**, Desktop full **153**, shadowJar **PASS**, MTP process E2E **11 stages PASS**, with zero failed/error/skipped tests. Commands use `gradlew.bat`, `--offline --no-daemon --console=plain`; exact focused class selections and final task logs/XML are retained in the workspace report directory. The first focused attempt's value-class object-identity assertions were corrected to value/raw-bit assertions. Final review added negative-identity quaternion product coverage and repeated the affected validations.

5S original DLL/reviewed probe/report/evidence and 4,285 historical protected hashes; 5X/5Y artifacts; audited Bridge tree; MonakaProtocol/schema/generated/fixtures/native source are preserved. No native or ALVR rebuild is required with zero changes. Final branch must have 1–2 forward commits, normal origin push and clean tree, without base merge or force push.

## Next transport requirements

**Phase 2B-5AA — Trusted HMD Common-Pose Transport / Revocation Contract** is the single selected next phase. Structural facts now exist; the missing boundary is ALVR → Bridge → MonakaVR process transport, especially revocation with no new pose.

Define exact source identity/session/type/offset/origin/combined generation, observation ID/source locate time/clock domain, exact P/Q/validity facts, complete common mapping and result, ordered lifecycle/revocation, transport identity separated from source authority, bounded receipt/freshness and loss handling. Protocol work must use a reviewed generated/hash-pinned kit. Bridge owner implementation, persistent allocator/restart rules, calibration/IMU relation publication, registered ingress/time proofs and coordinated MTP invalidation remain gates before activation. 5Z PASS authorizes none of those runtime steps by itself.
