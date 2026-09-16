# Main/Fallback candidate checkpoint

This checkpoint intentionally implements only contract-independent policy/assignment logic plus a codec-free identity boundary that mirrors the current C2 namespace rules. It is safe to review before the wire-v2 kit is imported and is **not wired into MonakaRuntime, ConstraintResolver, MTP ingestion, IK writeback, or SteamVR output**.

Candidate files:

- `server/core/src/main/java/dev/monaka/tracking/revision/MainFallbackPolicy.kt`
- `server/core/src/main/java/dev/monaka/tracking/revision/MainFallbackAssignmentSet.kt`
- `server/core/src/main/java/dev/monaka/tracking/revision/LegacyAssignmentMigration.kt`
- `server/core/src/main/java/dev/monaka/tracking/revision/PersistentTrackerIdentity.kt`
- `server/core/src/main/java/dev/monaka/tracking/revision/MainFallbackSelector.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/MainFallbackPolicyTests.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/MainFallbackAssignmentSetTests.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/LegacyAssignmentMigrationTests.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/PersistentTrackerIdentityTests.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/MainFallbackSelectorTests.kt`

The policy candidate encodes the current Architecture Revision default only:

- usable Main `FULL` owns position and rotation, even if a fallback is available;
- otherwise Main position is unavailable;
- an externally assigned usable rotation fallback is preferred;
- if external fallback is unavailable, usable Main rotation may be used only when Main is explicitly `ROTATION_ONLY`;
- `NONE` never revives Main rotation merely because stale numeric data exists;
- an incomplete/malformed `FULL` sample fails closed and does not silently self-demote to rotation-only.

The assignment candidate encodes only configuration semantics that are independent of wire-v2 and runtime integration:

- every target has one explicit Main tracker and an optional explicit rotation fallback;
- legacy one-tracker-per-target mappings migrate to Main-only entries;
- migration never guesses an external fallback;
- assignment updates are immutable candidate snapshots, so changing one target does not mutate another or an earlier snapshot.

Legacy migration additionally models the old tracker-to-target shape explicitly. If more than one distinct legacy tracker maps to the same body target, migration returns an ambiguity result instead of selecting by quality, priority, order, recency, or identifier. Exact duplicate pairs are tolerated. This keeps the Architecture Revision rule that ambiguous assignments require explicit user selection and fail validation.

The identity candidate mirrors only the C2 namespace shape needed to prevent accidental tracker collapse inside candidate logic:

- runtime source lifetime is `(publisherId, sourceId)`;
- persistent logical tracker identity is `(publisherId, sourceId, trackerId)`;
- the same `trackerId` under a different source or publisher is a different tracker;
- body assignment, session, modality, mapping revision, quality, timestamps, ports/endpoints and connection order are deliberately absent from persistent identity.

`MainFallbackSelector` is still runtime-unwired. It accepts already-effective per-tracker usability state keyed by the full persistent identity. Only the explicitly assigned Main and optional fallback can participate. Unrelated observations never compete by quality/priority/order, and an observation with the same tracker name under another source cannot impersonate the configured tracker. A malformed FULL fallback is not treated as a usable rotation source.

`CandidateTrackingModality` is deliberately local to this isolated package. `PersistentTrackerIdentity` is likewise not a substitute for MonakaProtocol v2 models/codecs. During v2 integration, map the pinned protocol fields into the runtime model or replace these candidate types after reviewing the boundary.

For the next Codex pass: treat these files as tested-design candidates, not as an instruction to preserve names or structure. Reconcile them with the current Architecture Revision and the pinned v2 kit. If Codex has independently implemented the same policy, assignment model, identity key, selector, or legacy migration, prefer one implementation after semantic comparison; do not keep duplicate active runtime policies, identity stores, or assignment stores. Existing old `ConstraintResolver` behavior remains active until an explicit integration change is made.

No hardware or runtime validation is claimed by this checkpoint. Tests in these commits are unit-level specification tests only and remain `NOT RUN` until executed in a build-capable environment.
