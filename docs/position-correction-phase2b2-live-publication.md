# Phase 2B-2.1: committed live HIP geometry publication

This phase adds a live read boundary for the six-field [HIP body-model snapshot](position-correction-phase2b2-body-model.md). It does not implement or connect a predictor, PositionPrediction, position learner, solver injection or visible-output correction. Hardware/HIL: **NOT RUN**.

## Mutation audit

`SkeletonConfigManager.configOffsets` is private. The only direct writes are assignment/removal in `setOffset(config, value, computeOffsets)` and clear in detached `resetOffsets()`. No public mutable offset map is exposed. The following existing boundaries now participate in HIP publication:

| Path | Logical publication boundary | Existing side effects |
| --- | --- | --- |
| Either setOffset overload | One single operation | Storage write, affected node computation when enabled, height/neck-height calculation, VRC forceUpdate |
| Either map setOffsets overload | Whole map operation | Nested setters retain per-entry height/VRC effects; final node computation remains last |
| setOffsets(other manager) | Whole copy operation | Merge explicit source offsets, then copy source node offsets; unset source keys still do not clear destination keys |
| resetOffset | Single reset operation | Height-based default scaling, then existing setter |
| resetOffsets | Whole reset operation | Attached: nested resets; detached: clear plus optional node recomputation |
| resetAllConfigs | Whole outer reset operation | Offset reset followed by toggle reset and value reset, in existing order |
| loadFromConfig | Whole load operation | Nested offset setters, toggle/value application, then optional node recomputation |

Defaults are immutable enum values; initialization does not depend on Skeleton pose. HumanPoseManager constructors/load/reset/setters delegate to these boundaries. AutoBone's `applyConfig` and height scaling still invoke **separate single setters**, and its worker uses detached scratch configs/bulk copy. There is no new transaction spanning an entire AutoBone process or a caller loop. UserHeightCalibration calls resetOffsets. WebSocket → ProtocolAPI → RPC change-config invokes a single setter directly; the RPC reset path is queued on VRServer. Named-pipe/socket RPC paths and AutoBone processing also use different threads. No common legacy thread confinement is assumed.

## Strategy A: committed immutable mirror

`HipBodyModelPublication` owns a private six-offset mirror, a thread-local operation journal, a short commit lock, and one volatile immutable `Published(sequence, result)` reference. The mirror is initialized from effective defaults. It is not a lock around the existing configuration subsystem.

Each relevant storage assignment records its **requested effective value** (`value ?: offset.defaultValue`) into the current journal before node/height/VRC side effects. Detached clear records the six defaults. Bulk, reset and load reuse setters, but nesting on the same thread reuses the same journal. A different thread has its own journal; it cannot suppress or merge into another thread's unfinished batch.

At the outer operation's exit, after its existing side effects, the journal is removed from the thread-local. Under the commit lock only, the completed journal's deltas are applied together to the committed mirror, the existing snapshot factory validates them, and one new immutable reference is published. No legacy getter, Skeleton method, callback, logger or user-supplied map iterator runs under this lock. The public operation's numeric writes and side-effect order/count are retained.

Overlapping operations linearize in **publication commit order**. A completed single update can publish while another thread has an unfinished batch; when that batch completes, its complete journal commits atomically. Disjoint journals preserve each other's committed fields. This defines a coherent *committed configuration model*, rather than sampling the unversioned legacy EnumMap. It deliberately does not make legacy storage writes or Skeleton application linearizable: under overlapping unsynchronized calls, their individual write order can differ from publication commit order. Consumers must use the committed model, not combine it with fresh legacy offset reads or solved transforms.

As with the legacy API, a caller supplying a mutable map or another manager to copy must keep that input stable for the duration of the copy. The publication boundary groups the values actually accepted by that operation; it does not make an independently changing input map/source manager transactional. No predictor is wired to this boundary in this phase.

## Read, availability and identity

`SkeletonConfigManager.currentHipBodyModelSnapshot()` performs **one volatile reference read** and returns `HipBodyModelSnapshotResult`. It does not read six live offsets or capture on demand. Defaults publish `Available` at construction, before any Skeleton-dependent init work. There is no INITIAL/UNAVAILABLE timing gap and no computed-pose dependency.

A successfully completed operation containing relevant changes publishes at most once. An unrelated operation publishes nothing. Re-setting the same relevant value may republish and advance the internal publication sequence; the content-based identity is unchanged. Sequence is observation metadata, not a body-model epoch, and is not added to PositionPredictionEpoch. Model ID and epoch schema are the unchanged Phase 2B-2 values, including the signed-zero policy. Quiescent offline capture and live publication of identical effective geometry have identical fields and `BodyModelIdentity`.

NaN/Inf in the committed current geometry publishes `Unavailable(nonfinite_<field>)`, replacing any old valid current result. Correcting that value restores availability; restoring the old content restores its old epoch. Returned snapshots remain immutable forever.

If a logical operation throws after accepting relevant changes, the original exception still propagates, and the publication becomes `Unavailable(offset_mutation_incomplete)`. A caught nested failure also marks the outer journal incomplete. It does not expose a half-applied batch as an available model or silently claim that the old model is current. Revalidation then requires a successful operation covering all six center-chain fields (for example reset or a complete batch). Ordinary nonfinite rejection alone does not impose this extra full-batch recovery requirement. No publication callback is introduced.

## Atomicity scope

Guaranteed:

- Readers receive one immutable six-field tuple from one committed model state, or an explicit Unavailable result.
- Logical map bulk/copy, reset and load do not publish intermediate per-field states.
- Concurrent completed journals commit indivisibly; an unfinished journal is not a global publication-suppression flag.
- Invalid committed geometry fails closed; identity remains content-based.
- No lock is held during legacy callbacks or Skeleton updates, and the getter is lock-free.

Not guaranteed:

- Global thread safety/transactions for all SlimeVR config, its legacy EnumMaps, toggles, values or persistence.
- Snapshot/solved Skeleton pose synchronization or atomicity of node application.
- A transaction spanning AutoBone's separate setter calls, multiple RPC requests, or a changing source map/manager.
- HMD space/frame/session/pose-pairing proof, anatomical calibration or tracker-mount-to-HIP calibration.

The original `captureOffline()` remains unchanged in meaning: exclusive detached ownership on its construction thread, with live/attached capture rejected. Live consumers use publication instead; they do not relax the offline contract.

## Software tests and remaining gates

`LiveHipBodyModelPublicationTests` covers initial defaults; every relevant single setter/reset; same content; whole map/copy/reset/load boundaries; absence of intermediate publication inside batch/load iteration; unrelated settings; NaN/Inf/recovery; immutable old snapshots; failed-operation isolation; cross-thread journal isolation; and real HumanPoseManager/Main constraint/IK/computed-HIP independence.

Concurrency tests use latches/barriers and fixed iterations, with no sleeps. Two writers perform 800 complete A/B batches while a reader checks 12,800 snapshots; all observed available identities must be A or B. A separate reset/load test checks 3,200 reads against defaults or the fully loaded geometry. Failure reports identify the step/tuple. These tests are part of the normal Core suite alongside the existing offline, Rotation Correction, Hybrid stress, diagnostics and prediction-contract tests.

Live HIP publication is established. Remaining position prerequisites are trusted HMD connection/session lifetime, exact HMD CoordinateSpace/frame identity, coherent HMD position/orientation pairing, Main TRACKER_MOUNT → HIP_CENTER calibration, temporal pairing, MainDecoupledHipPredictor algorithm, position correction law and runtime integration. MainFallbackPolicy, Rotation Correction, OutputContinuity, HMD provenance and visible SteamVR behavior are unchanged. Hardware/HIL and SteamVR interactive validation: **NOT RUN**.
