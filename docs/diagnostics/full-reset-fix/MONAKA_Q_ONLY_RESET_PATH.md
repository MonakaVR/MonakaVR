# Monaka Q-only Reset path audit (Sol-B)

## Scope and execution record

- Task ID: FULL-RESET-SOL-B; OFFLINE read-only source audit, 2026-11-06.
- Requirement: supplied `MonakaVR_Q-only_FBT_FullReset…Orc` instruction, sections 3.2, 4.4, 5, 6 and T2–T10. Root/repository `AGENTS.md` were read and applied. Producer independence and normal-runtime continuity remain binding.
- Specified/used model: GPT-6.1 Sol / High, explicitly approved substitute for unavailable GPT-5.6 Sol / High. This agent was successfully spawned by the parent. No additional agent spawned.
- Worktree: `H:/tmp/6Dof/MonakaVR`; base/head supplied by parent: `85a9ead351abec6f56de37e1f854bbe96801ae33`, initially clean. No independent Git command was run in this audit.
- Editable scope: this document only. No production/test edits, tests, build, Git mutation, app, transport connection or physical operation performed.
- Verdict: source-level finding **ACCEPT** for design input; implementation acceptance remains **NOT_RUN**. SOFTWARE: **NOT_RUN**; PHYSICAL_HIL: **HIL_WAIT**; CUTOVER: **NOT_RUN**. No binary/kit SHA produced. Parent Astra G2 review pending.
- Excluded: RR-00F/R00, other repositories, producer control, wire/protocol changes, live hardware, solver-math redesign.

Source line references below describe the audited base, before the subsequent fix.

## Product path and observable acceptance boundary

The product path is:

```text
MVIP packet admitted by MtpObservationBackend
 -> MtpPoseAdapter/PoseObservation
 -> ObservationBackendRunner/ConstraintPipeline freshness
 -> ConstraintResolver
 -> EffectiveConstraint
 -> ResolvedTrackingPose.ikConstraint()
 -> MonakaServerIntegration.tick / MonakaSolverComposition.commitNormal
 -> ConstraintIkWriteback.apply/applySolver
 -> private Tracker raw rotation
 -> SkeletonInputView.rotations
 -> HumanSkeleton target tracker reference
 -> Tracker.getRotation at readSolverRotation
 -> FK transforms -> IK solve -> computed tracker output
```

Acceptance must compare **actual consumed rotation and bone/computed output**, with a real normal reset-aware Tracker as the behavioral oracle under matching raw Q, target, HMD reference, reset settings and operation order. Packet admission, Reset event completion and proxy storage alone do not establish the behavior.

### Admission and selection are already component-correct

- `mtp/MtpObservationBackend.kt:126–169` independently invalidates short-lived samples and rebuilds mapped observations when assignment generation changes. Its `historyGeneration` is sample/history state, not a reset calibration epoch. Reconnect/sample loss does not itself rewrite assignments.
- Backend lines 245–307 reject wrong convention/old mapping and invalidate incompatible/replaced source samples. A lost/absent state clears the sample. These are upstream admission decisions; they neither supply nor apply a consumer reset offset.
- `mtp/MtpPoseAdapter.kt:20–46` exposes P only for a valid FULL component and Q only for a valid orientation component. It converts protocol xyzw to runtime Quaternion(wxyz) without applying reset or producer-specific rotation. Provenance remains source metadata.
- `TrackerInputCapabilities.kt:29–38`: explicit Q/6DoF selection uses `mainTracker = sixDofSource ?: qSource`. Consequently explicit Q-only main identity is precisely its Q source identity.
- `ConstraintResolver.kt:29–55` recognizes explicit `sourceSelection.sixDofSource == null`; a complete FULL sample selected as Q-only becomes ROTATION_ONLY, position ownership is disabled, and Q retains its selected source/value. An incomplete malformed FULL is not promoted to Q-only success.
- `EffectiveConstraint.kt:7–18` contains resolved immutable values/source/quality/time, without reset state. `ResolvedTrackingPose.kt:35–43` wraps the same components and `ikConstraint()` reconstructs them without a transform.
- `MonakaRuntime.kt:57–103` pins one assignment/timestamp/constraint snapshot. Desktop `MonakaServerIntegration.kt:96–131` applies the existing correction/convergence pipeline then commits one IK map. `BodyRotationRuntime.kt:52–53` scopes its explicit-pair processing to non-null distinct Q and 6DoF choices, so pure Q-only is not a new full-pose correction teacher.

These stages explain why the already demonstrated P=false/Q=true path can work while Reset remains ineffective. They are not missing P/Q validity handling and should not be changed for this fix.

## Proven divergence at the private Tracker boundary

`ConstraintIkWriteback.kt:123–128` constructs **every** proxy with:

```kotlin
allowFiltering = false
allowReset = false
allowMounting = false
trackRotDirection = false
```

Line 137 writes resolved Q using `proxy.setRotation(rotation)`, i.e. raw storage (`Tracker.kt:491–500`). Flags are false by deliberate general-purpose proxy construction, not because the Tracker lacks a reset handler: every Tracker owns a `TrackerResetsHandler` (`Tracker.kt:161`). These flags also suit already-adjusted/common-space non-Q-only inputs, making a global flag flip unsafe.

Two independent gates prevent existing reset semantics from affecting Q-only:

1. `HumanSkeleton.kt:1686–1691`: Full Reset visits selected body tracker references but calls `resetsHandler.resetFull(referenceRotation)` only for `allowReset || isHmd`. The Q-only proxy is neither. Yaw similarly requires `allowReset` (1723–1727); Mount requires `allowMounting` (1763–1767).
2. `Tracker.kt:361–375`: `getAdjustedRotation()` applies the handler's reference/mounting/drift transform only when `allowReset` and the ordinary rotation-type conditions hold. Otherwise it returns its stored rotation (with any StayAligned correction). `getRotationNoResetSmooth()` reaches this function because `trackRotDirection=false`; `getRotation()` then optionally applies existing yaw smoothing (457–477).

Therefore even manually changing an offset without enabling the read-side gate would not suffice; enabling read-side adjustment without enrolling the object in Reset would likewise not establish correct state.

`allowMounting=false` is also behaviorally material. `TrackerResetsHandler.kt:312–318` calculates `gyroFix` only for a mounting-enabled tracker (or the special non-HMD HEAD case). `attachmentFix` and `yawFix` are subsequently derived from that state. Matching a normal IMU Full Reset requires reusing the appropriate existing handler branch, not assuming allowReset alone gives equivalent axis behavior.

For already normalized incoming Q, a new reset-aware proxy must start with `mountingOrientation=Quaternion.IDENTITY`, otherwise the handler's default HalfHorizontal mounting would alter the pre-reset value. This initial value preserves the previous no-reset product behavior; Full/Yaw/Mount then reuse the existing handler algorithms. No protocol/common-world Q is rewritten upstream.

## Actual FK consumption is established in source

- `ConstraintIkWriteback.kt:25–31` replaces managed raw targets with private proxies, includes capability-correct proxies in constraints, and includes rotational proxies in FK rotations. Position-only non-HEAD proxies are excluded from FK rotations.
- `HumanSkeleton.kt:460–474` assigns CHEST/HIP/etc. references from that view, not from raw-input registration. Lines 569–577 rebuild IK chains with view constraints and preserve the named IK calibration.
- `HumanSkeleton.kt:283–297` calls the same target Tracker's `getRotation()` and records exactly that returned Q, flags, read site and tick. Its `ConsumedRotationInput` does not read proxy raw storage as a substitute.
- CHEST rotation is used by `updateSpineTransforms` (929–946); leg targets by `updateLegTransforms` (1062 onward). `updatePose()` runs transform updates, bone updates, IK and computed tracker updates (638–668).

Thus the observed unchanged consumed Q is consistent with the two gates above. FK is not bypassing the private proxy or secretly applying another reset offset.

## Reset lifecycle in the current base

| Event | Proxy object/state in audited code | Consequence for a reset-aware fix |
| --- | --- | --- |
| Full Reset while valid | Same object. No writeback call or proxy reconstruction is triggered by the reset itself; handler is skipped because flags false. | Existing object must receive the ordinary handler update. |
| Next valid tick, same target/source/capabilities | Same object; only raw Q is updated at line 137. | Exact object retention naturally retains handler offsets; setter does not clear them. |
| Assignment generation changes, binding unchanged | `generation != assignment.generation` at 75 marks topology rebuild, but does not alone recreate the proxy. Stable name/capabilities remain equal. | Do not clear reset state merely because generation increments. |
| `refreshConstraintInputs()` | Recomputes skeleton references/chains from the supplied view. IK calibration retained by name. | Name retention preserves IK calibration, **not** the Tracker handler if its object was discarded. |
| Q invalid/stale/unavailable, no P | Lines 115–117 remove proxy; skeleton refresh removes it from FK/IK/reset reference lists. | Simply enabling Reset loses the offset on recovery. Retain handler/object separately while keeping input inactive. |
| Invalid solver component | Lines 110–113 remove active proxy, retaining calibration name. | Same separation: reject stale/invalid active Q while retaining same binding's consumer reset state. |
| Recovery | Null proxy causes a new Tracker and new handler at 121–128. | Reuse exact retained same-binding Q-only Tracker. |
| Name/capability change | Recreates proxy at 121. | Binding change must create clean state; transient Q-only validity must not masquerade as a binding change. |
| Target unassign/useAsIkConstraint=false | Target exits managed set; proxy removed at 85–90. | Retained state must also be pruned, keeping cache bounded by current explicit bindings. |
| Q identity change | Explicit Q-only main changes, hence stable name changes. | Old offset must not cross to another Q identity. |
| Close | Proxy map cleared, view removed (145–153). | Clear retained objects and private tick baseline as well. |

`historyRevision` on `apply` is explicitly unused (`ConstraintIkWriteback.kt:36`). It should not become an implicit reset invalidation signal: source history and consumer calibration have different lifetimes under normal-runtime semantics.

Dormant means no object in the active `proxies`/`SkeletonInputView`/tracker reset references. Keeping its handler in a separate bounded map must **not** keep its last Q feeding FK/IK or reset it from stale Q while unavailable.

## Non-Q-only invariants

1. Enable consumer reset only for explicit Q-only public-input selection: `useAsIkConstraint`, `sourceSelection != null`, `sixDofSource == null`, `qSource.mtp != null`. `hasPosition=false` alone also describes 6DoF fallback and legacy inputs; it is not the right policy predicate.
2. `SlimeTrackerPoseObservationAdapter.kt:34` already reads `tracker.getRotation()`. Enabling reset on a second proxy for that observation would double-apply independent reset semantics. Preserve its existing no-reset proxy path.
3. FULL Main and its positional calibration/precompensation must remain unchanged. `ConstraintIkWriteback.kt:99–108` rotates calibration offsets with the same no-reset rotation subsequently stored on the full-pose proxy. Changing that rotation would break the paired position/rotation meaning.
4. 6DoF FULL -> ROTATION_ONLY/fallback -> FULL is not an explicit Q-only assignment. Existing stable named IK calibration and output topology policies remain intact.
5. Keep `isInternal`/`isComputed` unchanged: code comments at 129–132 explain those flags remove the input from IK. Retain exact object registration with `FeedbackExclusion.registerOwnedOutput` so private solver input never becomes admitted raw feedback.
6. No source-name/vendor branching, producer lifecycle/configuration access, new wire fields, arbitrary heading quaternion or solver math changes.

## Minimal design options for parent selection

### Preferred: existing Tracker handler with explicit bounded lifecycle

- Only explicit MVIP Q-only bindings receive `allowReset=true, allowMounting=true`, unfiltered and non-direction-tracked as before; initialize normalized mounting to identity.
- Retain the exact Tracker, hence its exact `TrackerResetsHandler`/yaw interpolation state, by target plus explicit Q identity. Active map only contains currently valid components.
- Keep retained state across same-binding sample/history/session/generation changes. Prune on source/target/mode/unassign/useAsIkConstraint change and close; do not infer stable identity from a concrete producer name.
- Load ordinary consumer reset settings using existing `readResetConfig`, with saveMountingReset disabled on private ephemeral inputs. `TrackerResetsHandler.kt:481` otherwise calls `Tracker.saveMountingResetOrientation`, which writes global per-tracker configuration. Preserve mounting in memory only.
- Advance existing yaw interpolation privately using a monotonic product tick. `VRServer.kt:273–277` ticks only registered raw/output trackers, then invokes the Monaka before-pose hook; private proxies are never in that raw tick list. Merely setting yawResetSmoothTime would leave its interpolation frozen.
- The composition's once-only tick reservation can call a narrow `writeback.advancePrivateResetTime(tick.nowNanos)` for retained Q-only instances. Compute nonnegative elapsed seconds from the deterministic monotonic snapshot clock; do not advance on rejected duplicate/rollback commits, do not turn a first large epoch value into a huge delta. No new quaternion math is required: reuse `yawResetSmoothing.tick(delta)` already called by ordinary `Tracker.tick` (`Tracker.kt:331`). Ensure every accepted commit owner uses the same seam exactly once.
- Updating only private yaw interpolation avoids unintentionally enrolling this correction-free proxy into raw device timeouts, StayAligned or drift capture behavior through the whole `Tracker.tick` call.

### Alternative: explicit private-boundary transform

Use only if Tracker integration proves unsuitable. It still requires a single authoritative existing reset handler and lifecycle; copying quaternion fields into a new Monaka reset algorithm would be a second system and is rejected. It offers no apparent simplification over retaining the existing Tracker.

### Rejected shortcuts

- Global `allowReset=true` for every proxy, or predicate `rotation != null && position == null`.
- Recreating a proxy on each reset, generation or recovery and assuming `retainedCalibrationNames` carries handler offsets.
- Holding stale Q active to preserve the object; resetting dormant objects with old raw Q.
- Copying the reset quaternion onto admitted MVIP data, or modifying Bridge/protocol/producer settings.
- Full-only ad hoc heading multiplication or test-only expected quaternion copied from the writeback implementation.

## Existing coverage and required T2–T10 additions

Existing tests were **read, not executed**:

- `MvipSolverBoundaryTests.kt:152–252`, parameterized CHEST/LEFT_UPPER_LEG, traverses encoded public fixture -> runtime -> composition -> real manager -> actual consumed FK diagnostics; demonstrates P isolation, Q response, missing/stale Q removal and calibration retention. It does not reset. Its pre-reset expectation consumed Q equals admitted Q is valuable and should remain true with identity initial mounting.
- `MvipSolverBoundaryTests.kt:38–148` traverses real full-pose/loss/recovery and single-commit ownership. It is a non-Q-only regression baseline, not a reset-state oracle.
- Desktop `SolverCompositionIntegrationTests.kt:151–200` invokes real Full Reset with explicit CHEST Q-only, captures events, admitted Q, flags/read site, bone/computed presence. It currently asserts neither consumed-Q transformation nor numeric bone/computed orientation; it can pass the audited defect. Strengthen it with an independent normal-Tracker/reference oracle.
- `MonakaSolverCompositionTests.kt` provides duplicate/rollback/owner switching/pause lifecycle checks; extend only as needed for the new private-time seam. `HybridFoundationTests.kt` covers full-pose topology/calibration stability and existing-input compatibility.

| Test | Required observable evidence |
| --- | --- |
| T2 Full | Nontrivial initial Q and nontrivial HMD yaw; normal reset-aware Tracker and explicit MVIP Q-only have matching consumed Q after a real manager Full Reset. Confirm before/after differ where expected and admitted Q remains unchanged. |
| T3 consumed boundary | Real `beginDiagnosticInputFrame` -> manager update -> finished reads, P=false/Q=true/P scalars absent; compare rotation modulo quaternion sign/tolerance to the independent ordinary Tracker oracle. |
| T4 skeleton/computed | Numeric target bone and computed orientation from real HumanPoseManager/HumanSkeleton change to the same reference result; no setter-only success. |
| T5 position isolation | After reset, fix Q while changing full-capable input P substantially. Consumed Q, bones and computed pose stay unchanged. |
| T6 Q response | After reset, change Q and verify consumed Q plus bone/computed rotation move relative to the current reset reference; no frozen stored orientation. |
| T7 repeat Full | Two different current raw Q values, two real Full Resets; compare each to independently reset normal Tracker and verify no accumulated/double offset. |
| T8 Yaw/Mount | Run existing handler branches, including nonzero configured yaw smoothing over successive product tick times. Match normal Tracker semantics; prove private interpolation completes and Full clears smoothing. Mount is body/type-sensitive: include at least CHEST plus leg/arm case as appropriate. |
| T9 rebuild/binding | Exact object/handler retained for same-binding forced generation rebuild and session reconnect; reset offset not doubled. A real Q-source switch, explicit 6DoF-mode switch, target unassign/reassign and opt-out each clear old private reset state. Reject duplicate/rollback commits without advancing time. |
| T10 loss/recovery | Valid -> invalid/stale -> valid keeps the same binding's reset baseline while unavailable Q contributes no FK/IK/reset target. Reset while absent cannot recalibrate from stale Q. Same identity reconnect recovers; changed identity does not inherit old offset. |

Negative checks should cover Slime already-adjusted proxy, paired FULL 6DoF and 6DoF rotation fallback remaining no-reset, feedback exclusion, no P introduction, and unavailable/malformed input never becoming held active Q. Dynamic production tests should exercise the desktop composition, not only direct writeback calls.

## Handoff

Static evidence supports the private Tracker reset enrollment/read-side divergence as the defect. The state-retention and private interpolation gaps are adjacent implementation obligations, not proof of a physical quality result. Parent owns final normal/Q-only comparison and design; sole implementation owner must first produce a failing behavioral regression against this base, then implement/test the selected scope. No test/build PASS or READY_FOR_FULL_RESET_HIL claim is made by this audit.
