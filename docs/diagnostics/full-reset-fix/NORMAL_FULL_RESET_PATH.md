# Normal Full Reset path audit

## Scope and evidence status

| Item | Value |
| --- | --- |
| Task | Sol-A / Normal Reset Path Auditor |
| Requirement | User Full Reset task attachment dated 2026-11-06, sections 3.2, 4 and T1–T10 |
| Repository | MonakaVR only |
| Base / inspected HEAD | `85a9ead351abec6f56de37e1f854bbe96801ae33` |
| Branch | Inspected on `diagnostic/full-reset-observability-20261106`; parent subsequently created `fix/q-only-full-reset-20261106` at the same base |
| Assigned / used model | GPT-6.1 Sol / High; approved substitution for unavailable GPT-5.6 Sol / High |
| Agent | `/root/normal_reset_audit`; spawn succeeded |
| Allowed mutation | This document only; no product/test changes, Git mutations, builds, tests, apps or hardware |
| Software judgment | `NOT_RUN` — static path audit completed; no executed test PASS claimed |
| Physical HIL / cutover | `NOT_RUN` / `NOT_RUN` |

Root and repository `AGENTS.md` were read and applied. Historical Task5 documents were read for context; their older producer-specific guidance does not override current producer independence. No new protocol dependency is required by this audit. Source references below are repository-relative and describe the inspected base. Read-only `git status --porcelain=v1` encountered an access-denied nested submodule; a subsequent `git status --porcelain=v1 --ignore-submodules=all` showed only the untracked task-document directory. Nested submodule status was not verified.

## Product entry and reset dispatch

1. The right-bottom proportions-page button is `ResetButton type={ResetType.Full}` (`gui/src/components/onboarding/pages/body-proportions/ScaledProportions.tsx:471–474`, when `state.alonePage`). The toolbar also creates a Full reset button (`gui/src/components/Toolbar.tsx:178`). Both use `gui/src/hooks/reset.ts:47–51`, which sends a `ResetRequestT` with the chosen reset type and body-part group.
2. `server/core/src/main/java/dev/slimevr/protocol/rpc/reset/RPCResetHandler.kt:59–69` recognizes `ResetType.Full`, selects the configured/requested delay, and schedules Full reset. It does not reinterpret Full as Yaw or Mounting.
3. `server/core/src/main/java/dev/slimevr/VRServer.kt:377–390` reports STARTED while counting and queues `humanPoseManager.resetTrackersFull` on completion; FINISHED is sent after that call. The direct API at `VRServer.kt:335–336` likewise queues the manager call.
4. `HumanPoseManager.kt:485–498` calls the real skeleton reset, then aligns consumer output handlers and logs drift if a server is present. These later operations do not provide the input tracker reset transform.
5. `HumanSkeleton.kt:1670–1705` brackets the existing algorithm with diagnostics. A completed observation proves entry/exit, not that every body input was reset.

All remaining main-source references under the reset/solver discussion are in `server/core/src/main/java/dev/slimevr/` unless prefixed otherwise.

## Tracker representations and actual consumed rotation

| Representation | Actual code | Meaning |
| --- | --- | --- |
| Raw Q | `tracking/trackers/Tracker.kt:120,491,497–500` | `_rotation`; `setRotation` replaces this raw value and records accepted orientation sample provenance. |
| Reference/body adjusted Q | `Tracker.kt:361–375`; `TrackerResetsHandler.kt:186,224–245` | Raw Q, optional Stay Aligned yaw, mounting/reset/constraint transforms, then optional drift compensation. This is the body rotation used for FK. |
| Identity adjusted Q | `Tracker.kt:436–450`; `TrackerResetsHandler.kt:253–259` | Separate no-mounting zero-reference reset transform for debugging/visualization; it is not the solver's body-reference value. |
| Filtered, unsmoothed Q | `Tracker.kt:471–476` | If `trackRotDirection=true`, reads filtering-handler output; otherwise computes adjusted Q immediately. |
| Solver-consumed Q | `Tracker.kt:457–465`; `tracking/processor/skeleton/HumanSkeleton.kt:283–298` | `getRotation()` additionally left-multiplies any active yaw-reset interpolation. `readSolverRotation` records and returns exactly that FK read. |
| Bone/computed Q | `HumanSkeleton.kt:925–946,1274–1293` | FK applies consumed Q to bones, constraints and IK run, then computed output stores the tracker-bone global rotation with its rotation offset removed. |

There is no single stored `adjustedRotation` field replacing raw Q. The tracker owns a persistent `resetsHandler` (`Tracker.kt:161`), whose offsets are applied when adjusted rotation is computed. Mount-adjusted and heading-adjusted Q are stages of this calculation, not additional independent input samples.

Ignoring Stay Aligned, drift, smoothing and flex sensors, let `q` be raw Q, `M` manual `mountingOrientation`, `G` `gyroFix`, `A` `attachmentFix`, `F` `mountRotFix`, `T` `tposeDownFix`, `Y` `yawFix`, and `C` `constraintFix`. For a non-HMD body tracker the existing reference transform is:

```text
q_ref = C * Y * F^-1 * (G * q * M * A) * F * T
```

This order is taken directly from `TrackerResetsHandler.kt:224–245`. `M` defaults to `HalfHorizontal`, a 180-degree Y rotation (`:23–28,47–48`); it is applied for non-head-HMD inputs even when `allowMounting=false`. Identity debug Q instead uses the distinct `gyroFixNoMounting`, `attachmentFixNoMounting`, `yawFixZeroReference`, and `constraintFix` (`:111–114,253–259`). It must not be used as the body-reference oracle.

## What normal Full Reset changes

Normal UDP IMU trackers are created with `allowFiltering=true`, `allowReset=true`, `allowMounting=true`, and non-internal/non-computed defaults (`tracking/trackers/udp/TrackersUDPServer.kt:225–242`). Skeleton input selection uses actual tracker objects, excluding internal trackers and `status.reset` inputs (`TrackerUtils.kt:18–40`).

`HumanSkeleton.resetTrackersFull`:

- Starts with identity reference, resets the selected head first if its body part is included, then reads its `getRotation()` as the body reference (`HumanSkeleton.kt:1676–1683`). Head reset is a special unconditional call; ordinary body inputs do not have that exception.
- Iterates the current skeleton body-input fields in `trackersToReset` (`:1616–1667`), not all server trackers or computed output trackers. An input must satisfy `allowReset || isHmd` and the body-part filter (`:1686–1690`). Despite the stale nearby comment, `needReset` is not a requirement.
- Resets floor-clip level conditionally, leg buffers, localizer and IK offsets (`:1693–1700`). Thus hand/position changes can be visible even when a non-reset-aware body Q is unchanged.

For an ordinary noncomputed rotational body input, `TrackerResetsHandler.resetFull` (`:282–363`):

1. Advances correction-reset generation and clears `constraintFix`.
2. Selects configured arm T-pose adjustment; CHEST has identity `tposeDownFix`.
3. Records the old adjusted raw orientation for drift diagnostics.
4. Computes `mountingAdjustedRotation = raw * M`.
5. With `allowMounting=true`, replaces `gyroFix` with inverse yaw of the mounted input (including T-pose adjustment).
6. Replaces `attachmentFix` to cancel current pitch/roll/attachment orientation. Ordinary body path uses `(gyroFix * mountingAdjustedRotation)^-1`.
7. Recomputes the separate identity-debug reset offsets.
8. Replaces `yawFix` to match the reference's unit Y-axis projection, clears yaw smoothing, calculates optional drift, resets Stay Aligned, clears `needReset` and resets filtering quaternions.

Raw `_rotation` is not rewritten. Full Reset stores fresh offsets; it does not issue an upstream sensor/producer command. It also does not change source selection or assignments. Existing noncomputed `mountRotFix` is retained; Full Reset is not equivalent to clearing or recalibrating automatic mounting (`:320–325` only overrides it for computed non-head input). Flex, computed and head cases have their existing specific branches.

For CHEST, no automatic mounting, disabled corrections/filtering/smoothing, the immediate Full-reset result is the head reference projected to yaw: `H = reference.project(POS_Y).unit()`. It is not necessarily identity, and not the full tilted head quaternion. This is the existing test expectation in `server/core/src/test/java/dev/slimevr/unit/SkeletonResetTests.kt:18–40` and `ReferenceAdjustmentsTests.kt:83–113`.

## Exact effects of `allowReset=false`

This flag has more than a request eligibility effect:

- A normal non-head/non-HMD body input is skipped by the skeleton Full/Yaw reset loops (`HumanSkeleton.kt:1688,1724`).
- `Tracker.getAdjustedRotation` skips `getReferenceAdjustedDriftRotationFrom` (`Tracker.kt:370–375`), so its handler's offsets cannot affect the solver read. Stay Aligned is applied before this gate; active yaw smoothing is applied later by `getRotation`. Therefore “always returns byte-for-byte raw Q” is too broad in general.
- When filtering and direction tracking are disabled, and Stay Aligned/smoothing are identity, `getRotation()` is raw Q. This matches the current private proxy configuration.
- Identity-adjusted debug rotation is also gated (`Tracker.kt:445–450`).
- `needReset` changes on status transitions and body-assignment changes are gated (`Tracker.kt:194–196,211–213`).
- The handler still exists, and calling `resetsHandler.resetFull` directly is not itself blocked by `allowReset`. Such a direct call is not proof of solver reset: the read-side gate remains.

Other gates matter independently: reference adjustment also requires `trackerDataType == ROTATION` and excludes `isComputed && isInternal`; skeleton selection excludes any `isInternal` input. The existing proxy uses default `isInternal=false,isComputed=false`, with exact-object feedback exclusion (`dev/monaka/tracking/ConstraintIkWriteback.kt:124–131`). “Private proxy” must not be confused with setting the Slime internal flag.

## Full / Yaw / Mounting distinctions

| Reset | Eligibility and changes |
| --- | --- |
| Full | Body `allowReset || isHmd`; head special case. Rebuilds attachment/gyro/yaw and debug offsets, resets Stay Aligned/filter state, buffers/localizer/IK offsets. Normal CHEST becomes head yaw at reset. |
| Yaw | Body `allowReset`; head only if resettable and noncomputed. Replaces yaw/debug-yaw offsets, clears constraint correction, updates drift, resets Stay Aligned/filter state, and optionally interpolates yaw over configured time. Preserves attachment and mounting state; only leg buffers are reset at skeleton level. `HumanSkeleton.kt:1710–1729`; `TrackerResetsHandler.kt:380–418`. |
| Mounting | Body `allowMounting`; special head branch. With a server, blocked when a selected reset-list input still needs Full reset. Uses the user's mounting pose, body part and arm mode to replace `mountRotFix`, resets filter, leg buffers/localizer. Can save automatic mounting if explicitly configured. Does not perform another Full reset. `HumanSkeleton.kt:1738–1792`; `TrackerResetsHandler.kt:426–483`. |

`allowMounting` requires `allowReset` (`Tracker.kt:237–239`). Merely enabling `allowReset` makes Full Reset cancel current CHEST attitude, but leaves `gyroFix` untouched for a non-head body input (`TrackerResetsHandler.kt:312–318`). This affects later movement axes even when the immediate reset result matches.

For normalized Q, `M=I`, CHEST, `F=T=C=I`, no prior gyro offset, and reset raw `q0`:

```text
allowMounting=false: post-reset q1 -> H * q1 * q0^-1
allowMounting=true:  post-reset q1 -> H * G * q1 * q0^-1 * G^-1
                    G = inverse yaw(q0)
```

Both become `H` at `q1=q0`. A test that only checks the reset frame cannot distinguish their post-reset motion semantics. The second follows normal IMU gyro/attachment axis treatment; that is the better match for the task's normal-IMU semantic reuse, if Q-only private input is intended to support existing Full/Yaw/Mount operations. This is a design recommendation for the parent, not an implemented choice.

## Filtering and lifecycle

- With `trackRotDirection=true`, accepted raw writes become adjusted filter input on `dataTick` (`Tracker.kt:339–344`); `tick` advances filtering/yaw interpolation/Stay Aligned (`:330–332`). `allowFiltering=false` alone does not make raw writes immediately visible if direction tracking stays enabled.
- Full reset resets moving-average quaternions from the newly adjusted Q (`Tracker.kt:558–559`) and cancels yaw interpolation (`TrackerResetsHandler.kt:353`). A deterministic proxy can retain existing `allowFiltering=false,trackRotDirection=false`; reset adjustment still works on immediate `getRotation` reads.
- Offsets belong to the Tracker/TrackerResetsHandler object. `setRotation`, `heartbeat`, `dataTick`, reconnect-generation updates and ordinary status changes do not erase them (`Tracker.kt:133–135,190–206,339–351,497–500`). A status transition can mark `needReset` and trigger skeleton refresh but is not an implicit Full reset.
- `HumanSkeleton.setTrackersFromList` copies the list while retaining tracker object identities, reselects fields and rebuilds arm/IK topology; it does not create replacement Tracker reset handlers (`HumanSkeleton.kt:460–584`). Reusing the same normal input object retains offsets across a refresh. Creating a new Tracker loses those offsets by construction.
- Current private writeback deletes proxies on rejection/no-data and replaces them when name or P/Q topology changes (`ConstraintIkWriteback.kt:111–128`). If reset-aware Q state is added, transient validity loss and unrelated assignment generation/topology refresh must not silently erase it. Conversely a true Q-source/body relation replacement must not inherit another input's reset calibration. The companion Q-only audit owns precise stable-key design.

## Comparison and integration pitfalls

Current normal path:

```text
raw sensor Q -> same Tracker -> Full reset stores offsets
 -> dataTick adjusted/filter path or immediate adjusted getRotation
 -> HumanSkeleton.readSolverRotation -> FK -> constraints/IK -> computed output
```

Current private Q path at the inspected base:

```text
resolved Q -> private Tracker.setRotation
 -> allowReset=false excludes body reset AND bypasses read-side offsets
 -> HumanSkeleton.readSolverRotation -> unchanged resolved Q
```

This divergence is demonstrated by two independent code gates, not inferred from an isolated constructor flag. The companion audit must establish which resolved-Q provenance needs normalization/reset and how its lifetime is keyed.

Minimal normal-semantic integration must consider all of these together:

- Initialize normalized private Q `mountingOrientation=IDENTITY` once; default HalfHorizontal would rotate pre-reset normalized Q by 180 degrees after reset adjustment is enabled. Reinitializing mounting each tick would clear automatic mounting and mutate correction epoch.
- Reuse existing Tracker reset state rather than composing a second Monaka reset quaternion system.
- Keep normalized public Q unchanged in the input/resolver. Apply consumer reset only at the appropriate private solver boundary.
- Avoid reapplying reset/mount to an already adjusted local Slime observation: `SlimeTrackerPoseObservationAdapter.kt:37` supplies `tracker.getRotation()`.
- Preserve full-position/orientation input pairing and the existing IK effective-target precompensation. A blanket reset on all proxies can invalidate the paired FULL contract and the comment/assumption in `ConstraintIkWriteback.kt:101–103`.
- Keep feedback-exclusion ownership, P/Q capability flags and the existing actual-consumption observer unchanged.
- Private Mount reset must not accidentally persist to generic tracker config: `TrackerResetsHandler.saveMountingReset` defaults false, but setting it true permits `Tracker.saveMountingResetOrientation` to write through global `VRServer` (`Tracker.kt:302–305`; handler `:480–481`).

## Suitable acceptance oracle

Existing `SkeletonResetTests` fixes normal Full/Yaw/Mount behavior and `MountingResetTests` covers normal mounting/yaw combinations. `ReferenceAdjustmentsTests` has active Full/Yaw factories, but its general follow-up rotation factory is explicitly disabled (`:62–65`); do not count it as coverage of post-reset motion. `FullResetDiagnosticsTests` proves observer transparency and raw-Q preservation, not private Q reset behavior.

The implementation owner's new tests should use observable product behavior:

1. Feed a nontrivial tilted CHEST Q and a head with nonzero yaw through real HumanPoseManager/HumanSkeleton. Establish unchanged raw/source Q and pre-reset consumed Q, then invoke the real manager Full reset.
2. Assert immediate consumed CHEST Q equals analytical `H = reference.project(POS_Y).unit()`, with quaternion sign-insensitive tolerance. Also assert `has_position=false,has_rotation=true`, absent consumed P, and expected real bone/computed orientation. Event count or proxy storage is insufficient.
3. Change Q after reset by noncommuting pitch/roll/yaw rotations. Compare with a separate normal resettable Tracker baseline using the same normalized `M=I`, body target and reset configuration; supplement parity with the analytical reset-frame `H` check. This avoids cloning implementation math as the only oracle.
4. Repeat Full reset at a different raw Q/head reference, check the new `H`, unchanged accepted raw Q and continued post-reset response. This detects offset accumulation/freeze.
5. Exercise Yaw and Mount using existing normal reset behavior rather than a Full-only special case. For Mount, use a meaningful mounting pose, not an upright quaternion whose projected-up vector gives a degenerate heading.
6. Repeat P-only perturbation with Q fixed, transient loss/recovery, unrelated topology rebuild and assignment generation change. Inspect consumed rotation and real output after recovery. Then explicitly replace Q source/body relation and verify obsolete reset state is not transferred.
7. Add negative evidence: at the original base, identical Q-only input/reset sequence leaves consumed Q unchanged; a source already carrying normal Slime adjustments and a paired FULL input must remain outside the new private reset application.

No build or test command was run by this read-only auditor. Final SOFTWARE/HIL readiness remains the implementation and independent acceptance gates' responsibility.
