# Phase 2B-6F — Solver Effective-Target / IK Calibration Semantics

Solver position reference semantics and IK effective-target writeback are implemented.
6D application and 6E continuity remain IMPLEMENTED / DORMANT. Runtime orchestration
is implemented in injected synchronous 6G; production caller remains NONE.

## Defect and source proof

6D correctedPosition and 6E fallback anchors already represent HIP_CENTER. The
previous generic writeback stored them directly in private proxy.position.
`IKConstraint.getPosition()` then applied the preserved mount calibration again:

```text
effectivePosition = tracker.position + (tracker.getRotation() * rotationOffset).sandwich(offset)
```

`ArchitectureRevisionTests.calibrationSurvivesFullRotationFallbackRecoveryPauseAndOtherTrackerRebuild`
proves private HIP calibration can exceed 0.1 m and survives Main/fallback,
pause/resume and topology rebuild. Zeroing that offset would break existing Main.
6E also interpolated fallback HIP_CENTER toward raw Main tracker mount origin.
These endpoints represent different physical points when mount offset is nonzero.
Orchestration cannot safely connect those semantics until both defects are fixed.

Source chain:

1. `TrackerPosition.HIP` and `BoneType.HIP` both use `BodyPart.HIP`.
2. `IKSolver.getConstraint` binds by exact bodyPart; chainBuilder terminates the
   positional chain at central `HumanSkeleton.hipBone` (BoneType.HIP).
3. `IKChain.resetTrackerOffsets()` resets computedTailPosition to
   `bones.last().getTailPosition()`; child base constraints use that same junction.
4. HIP effective reset target is therefore central hipBone tail, the HIP_CENTER
   physical point fixed in 6A, rather than the offset computed HIP tracker output.

`SolverPositionReferenceSemanticsTests` checks that target against the actual
IKConstraint as well as characterizing the old double application.

## Typed solver boundary

Generic `EffectiveConstraint` remains the resolver/pipeline/direct-selection type.
`SolverEffectiveConstraint` is internal and contains target, position, rotation
and explicit `SolverPositionReference`:

| Reference | Meaning |
| --- | --- |
| NONE | No positional component |
| TRACKER_ORIGIN | Raw positional tracker origin; existing IK calibration applies |
| IK_EFFECTIVE_TARGET | Already calibrated solver physical target; HIP correction uses HIP_CENTER |

`EffectiveConstraint.solverConstraint()` selects NONE or TRACKER_ORIGIN solely
from positional presence. It retains exact component objects. Source-prefix
inference is prohibited; provenance never selects a reference mode.

6D `candidate.solverConstraint()` replaces ambiguous generic `ikConstraint()`.
It explicitly returns IK_EFFECTIVE_TARGET. No inverse calibration is applied by
6D, 6E, predictor or teacher. 6E result.constraint is also typed: fallback and
reacquisition use IK_EFFECTIVE_TARGET; MAIN_DIRECT uses the raw adapter.
No solver-target conversion into PoseObservation, pure prediction or direct output exists.

## Writeback and calibration lifecycle

`ConstraintIkWriteback.apply` preserves its generic production API and adapts raw
inputs to the internal `applySolver` sink. For the tick's selected rotation Q:

```text
O = (Q * currentRotationOffset).sandwich(currentOffset)
TRACKER_ORIGIN:      proxy.position = P_raw
IK_EFFECTIVE_TARGET: proxy.position = P_target - O
actual IK target:    (P_target - O) + O = P_target
```

The proxy disables filtering, mounting and reset transforms. Its getRotation
returns the selected numeric value (Tracker may return a new equal Quaternion).
Writeback uses that exact value, without normalization, with quaternion order
`trackerRotation * rotationOffset`. Rotation metadata is untouched. Position-only
input uses the proxy's identity rotation. Synthetic axis golden tests distinguish
the correct order from its reverse. Valid nonunit input remains numerically exact.

`IKSolver.calibrationFor(name, bodyPart)` reads current active IKConstraint
calibration first, then retained calibration. It uses the existing exact pair
`tracker.name to tracker.trackerPosition?.bodyPart`. No prefix matching, whole-map
copy on this path, premature cache or invented calibration revision is used.
No current calibration means zero offset and identity rotationOffset, matching a
new IKConstraint. Stable identity remains `monaka-private:TARGET:assignedMainId`.
Different name/body/assignment cannot borrow the previous calibration.

Reference mode is independent of capability. Full fallback, reacquisition and Main
retain the same proxy, masks and topology. Calibration is never reset, cleared,
deleted or moved to a duplicate proxy on a mode switch. Existing preserveCalibration
and retained names govern actual capability/assignment/topology changes.

Each input, calibration, combined quaternion, rotated offset and final subtraction
is checked for finite values; invalid/zero quaternion and overflow fail closed.
Typed `ApplyResult.Rejected(reason)` reports reference/input/calibration failures
and precompensation overflow. Rejection removes the stale active input while
retaining its calibration name across rebuild; no NaN/Inf is stored or clamped.
Generic valid raw behavior is unchanged; malformed raw input is rejected at the
boundary. `Paused` preserves existing pause behavior. `Applied` acknowledges
the selected input (including explicit absence).

`effectivePositionTargetSnapshot` delegates to the actual active
IKConstraint.getPosition through exact-key IKSolver diagnostics. It is read-only,
diagnostic/test only, and never source-selection authority or computed output.

## Current Main projection and same-point interpolation

`ConstraintIkWriteback.projectMainEffectiveHipTarget(raw, assignment, now)` reads
current exact-key solver calibration and calls the validated factory:

```text
P_main_effective = P_raw + (Q_raw * currentRotationOffset).sandwich(currentOffset)
```

`MainEffectiveHipTargetResult` is Available(MainEffectiveHipTarget) or
Unavailable(SolverTargetFailure). The private-constructor target binds HIP,
HIP_CENTER, derived position, position sourceId/quality/observedAt, assignment
generation, projection tick, exact raw component bundle and Main relation. Only current assigned
Main FULL, valid position/rotation and sample times can project. Missing rotation,
foreign ownership, invalid calibration and overflow yield unavailable, never zero.
6E validates the projected value against its exact current raw bundle/assignment and tick.
Cold or already direct Main retains raw behavior without requiring a projection.

Reacquisition, including the completion tick, requires a current projection:

```text
u = elapsed / reacquireDuration
P_selected_effective = anchorHIP_CENTER * (1-u) + currentMainEffectiveHIP_CENTER * u
first return: u=0, actual target = anchor
midpoint: u=0.5, actual target = lerp(anchor, current effective Main, 0.5)
completion: TRACKER_ORIGIN, actual target = current effective Main
```

Main raw position and rotation may move every tick; the current endpoint is
reprojected. Rotation remains the exact current Main component. Derived position
observed time is min(anchorObservedAt, mainEffectiveObservedAt). Completion returns
the raw Main component objects with TRACKER_ORIGIN; there is no extra jump caused
by switching reference modes. Existing elapsed-time, invalidation, context,
lineage, ownership and reloss rules remain intact.

Projection and writeback must run in the same server-thread phase with no
calibration reset/rebuild between them. Dormant software tests enforce this
ordering; 6G now enforces it in the injected synchronous pipeline. No cross-thread mutable cache or
synthetic revision is added. Explicit reset changes the next Main projection and
precompensation; it does not invalidate the anchor's physical HIP_CENTER point.

Teacher-side MainTrackerMountCalibration and IK reset calibration have different
identity/acquisition contracts. The projector never substitutes the teacher mount
snapshot. A known aligned synthetic geometry agrees numerically, but does not
prove production identity equivalence or mounting quality.

## Verification and status

Software tests cover old-path reproduction, zero/nonzero offset, nonidentity
rotationOffset/tracker rotation, multiplication-order golden, nonunit rotation,
same proxy and retained calibration, explicit reset coherence, signed large finite
targets, invalid references/calibration and arithmetic overflow. The manual end-to-end
path uses actual 6D preparation -> 6E selection -> private writeback -> active IK
target diagnostics at fallback, first return, midpoint, moving Main and completion.
Existing application, continuity, ArchitectureRevision and positional IK regressions
remain required, along with full Core/Desktop, shadowJar and MTP process E2E.

Production correction caller: NONE. MonakaRuntime remains unchanged.
6G runtime orchestration: IMPLEMENTED / DORMANT / INJECTED; actual typed IK sink
integration: VERIFIED IN TEST / DORMANT. Production Position Correction IK: NOT CONNECTED.
Existing generic raw writeback continues. Predictor, learner, measurement,
selection pipeline, output continuity and Background IK remain unchanged.

OpenVR HMD: POSE_ONLY. Strong Trusted: UNSUPPORTED. Production Raw HMD:
BLOCKED BY BACKEND. 2B-5P: NOT READY. 5S HIL: pending.
6F HIL: NOT REQUIRED / NOT RUN: this is solver reference semantics remediation
with dormant software calibration tests, not physical tracker mounting validation.

[6G runtime orchestration](position-correction-phase2b6g-runtime-orchestration-foundation.md)
now binds immutable same-tick facts, exclusive learning, current Main projection,
one continuity selection and one actual typed writeback. The production Raw HMD
blocker remains. Next: **Phase 2B-6H — Production Integration Gate / Runtime Adapter Audit**.

Receipts: `build/reports/phase2b6f-solver-effective-target-semantics-20261008/report.md`.
