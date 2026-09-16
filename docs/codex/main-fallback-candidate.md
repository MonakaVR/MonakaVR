# Main/Fallback candidate checkpoint

This checkpoint intentionally implements only contract-independent policy code. It is safe to review before the wire-v2 kit is imported and is **not wired into MonakaRuntime, ConstraintResolver, MTP ingestion, IK writeback, or SteamVR output**.

Candidate files:

- `server/core/src/main/java/dev/monaka/tracking/revision/MainFallbackPolicy.kt`
- `server/core/src/test/java/dev/monaka/tracking/revision/MainFallbackPolicyTests.kt`

The candidate encodes the current Architecture Revision default only:

- usable Main `FULL` owns position and rotation, even if a fallback is available;
- otherwise Main position is unavailable;
- an externally assigned usable rotation fallback is preferred;
- if external fallback is unavailable, usable Main rotation may be used while modality is not `NONE`;
- `NONE` never revives Main rotation merely because stale numeric data exists.

`CandidateTrackingModality` is deliberately local to this isolated package. It is not a substitute for MonakaProtocol v2 and must not be serialized. During v2 integration, map the pinned protocol modality into the runtime model or replace this candidate type after reviewing the boundary.

For the next Codex pass: treat this as a tested-design candidate, not as an instruction to preserve names or structure. Reconcile it with the current Architecture Revision and the pinned v2 kit. If Codex has independently implemented the same policy, prefer one implementation after semantic comparison; do not keep two active runtime policies. Existing old `ConstraintResolver` behavior remains active until an explicit integration change is made.

No hardware or runtime validation is claimed by this checkpoint. Tests in this commit are unit-level specification tests only.
