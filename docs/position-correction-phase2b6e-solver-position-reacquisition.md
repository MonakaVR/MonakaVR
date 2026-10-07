# Phase 2B-6E — Solver Position Reacquisition / Hysteresis Contract

Solver position reacquisition is **IMPLEMENTED / DORMANT**. Runtime orchestration
is deferred to **Phase 2B-6F — Position Correction Runtime Orchestration Foundation**.

## Why this phase precedes orchestration

6D intentionally rejects application whenever the resolver base already contains
Main position (`BASE_POSITION_ALREADY_PRESENT`). Connecting that boundary directly
would switch the solver from corrected fallback HIP_CENTER to current Main
HIP_CENTER in one tick on Main FULL recovery. This conflicts with position
convergence from the IMU IK side while hysteresis decays. 6E closes the pre-IK
continuity boundary before adding orchestration. 6D's absent-position safety gate
and existing Main/Fallback selection remain intact.

`OutputContinuityController` operates on visible output after Background IK.
6E instead selects a solver-facing positional constraint before IK. It neither
uses nor modifies visible continuity or post-solve readback.

## Caller and result contract

`PositionCorrectionSolverContinuity.select` consumes one explicit tick bundle:
local monotonic `nowNanos`, `resolvedAtNanos`, expected `CoordinateSpace`, one
assignment snapshot, base HIP constraint, current 6C state snapshot, optional
already prepared 6D `Ready`. The result contains phase, `EffectiveConstraint` and
typed reason. It is neither an observation, prediction nor visible output and
has no conversion to those types. It never enters the observation store.

The caller must apply each selected constraint to its solver sink in order.
Selection is the commit point for anchor ownership. A prepared candidate that is
never selected does not affect the anchor. Actual runtime delivery/acknowledgement
belongs to future 6F; this dormant API does not claim a production writeback.
The manual compatibility test applies every selection to actual writeback/IK.

Required: `now >= 0`, `resolvedAt == now`, HIP target, enabled HIP assignment.
When state has lineage, its last advance must equal now. Uninitialized Main direct
with no lineage is allowed. A candidate must have `applicationAt == now` and match
base rotation, current correction/phase/lineage, space, assignment, prediction
provenance and assigned fallback source. `Ready` alone is not currentness authority.
Main position plus Ready is a malformed mixed bundle and fails closed.

Main FULL requires both components to have the assigned Main observation ID,
usable quality, finite values and observed times in `[0, now]`. Rotation also
requires finite squared norm > 1e-10. Non-unit valid quaternions are preserved
exactly. Resolver freshness remains caller-owned; no extra age threshold is added.

## State machine and numerical behavior

| Phase | Selected solver input / transition |
| --- | --- |
| UNINITIALIZED | Cold Main FULL → exact MAIN_DIRECT; Ready → FALLBACK_ACTIVE; otherwise UNAVAILABLE |
| FALLBACK_ACTIVE | Exact candidate `ikConstraint()`; each selection replaces the last fallback anchor |
| REACQUIRING | Position interpolates from copied anchor to moving current Main position; current Main rotation remains exact |
| MAIN_DIRECT | Exact current base Main constraint |
| UNAVAILABLE | No manufactured/held position; later Main with no safe anchor is direct; current Ready can restart fallback |

`PositionCorrectionReacquisitionTuning(reacquireDurationNanos)` is required;
duration must be strictly positive. **No defaults, production duration or HIL
tuned value exist.** Synthetic durations are test-only. There is no stable dwell,
loss-side blend or rotation blend.

```text
u = clamp((now - reacquireStartedAt) / reacquireDuration, 0, 1)
p_solver = p_anchor * (1-u) + p_main_current * u
```

The first Main return tick starts reacquisition at `u=0`, with position exactly
equal to the last selected corrected fallback position and rotation exactly the
current Main component object. The target updates each tick; the anchor stays
fixed throughout recovery. At elapsed >= duration, the exact current Main base
constraint is returned, restoring all position/rotation metadata without residual.

Time uses ordered nonnegative subtraction, never `start + duration` (which could
overflow Long). Double weighted interpolation avoids Float endpoint subtraction
overflow. Finite endpoints and `0 <= u <= 1` keep the convex intermediate bounded;
a defensive finite-output check still fails closed, never snaps to Main. NaN/Inf
endpoints are rejected before interpolation. Same-time identical input repeats
return the same output and progress. Rollback returns an empty rejected constraint
without mutating current state/progress. Other malformed/numeric failures discard
the anchor and return empty components; a later valid Main is direct.

## Anchor lifetime, context and metadata

The copied anchor holds the selected position and physical observed time, exact
space, assignment generation and full learning lineage. Context also retains the
assignment relation. Space revision, assignment generation/relation, any teacher
or prediction epoch change, null lineage or uninitialized correction state clears
anchor and reacquisition. Anchors are never transformed across contexts. Valid
Main goes direct; valid current fallback can seed a fresh anchor. Missing Ready
on loss clears the anchor, so an unavailable gap cannot revive stale position.
Main re-loss during reacquisition or direct mode selects exact current fallback
when present, otherwise becomes unavailable.

| Reacquisition component | Metadata |
| --- | --- |
| Position source | `monaka-private:position-correction-reacquire-v1:HIP` |
| Position quality | `DEGRADED` |
| Position observed time | `min(anchor.positionObservedAt, currentMain.position.observedAt)` |
| Rotation | Exact current Main `ResolvedComponent`, without normalization or blending |

No reapplication of correction is performed. The fallback candidate's corrected
position is used directly. No predictor, measurement, learner observe/gap advance,
6D prepare, pipeline ingest, clock, random source or runtime is invoked.

## Software compatibility and remaining gates

Tests manually apply fallback → reacquire → Main direct to real
`ConstraintIkWriteback`, `HumanSkeleton` and existing IK. Both HIP capability bits
stay true. The same private proxy remains the sole managed HIP in constraints and
rotations; unchanged assignment/mask causes no extra topology rebuild. Actual
positional extraction accepts the proxy, and computed HIP remains finite across
test-only solver ticks. This is software boundary validation, not physical quality.

Production caller: **NONE**. MonakaRuntime, production IK writeback call-sites,
Background IK, OutputContinuity, predictor, measurement, learning and 6D prepare
remain unchanged. Runtime orchestration and correction production writeback:
**NOT CONNECTED**. Existing generic writeback continues independently.

OpenVR HMD: **POSE_ONLY**. Strong Trusted: **UNSUPPORTED**.
Production Raw HMD: **BLOCKED BY BACKEND**. 2B-5P: **NOT READY**. 5S HIL: pending.
6E HIL: **NOT REQUIRED / NOT RUN**, because this is a dormant pre-IK state machine
with explicit synthetic duration and manual software compatibility only.

Next: **Phase 2B-6F**. One immutable same-tick bundle and assignment snapshot can
sequence resolve → pure prediction → optional teacher measurement → exactly one
observe OR gap advance → 6D prepare → 6E selection → one solver-ready map →
injected/manual writeback sink. The production Raw HMD blocker remains.

Receipts: `build/reports/phase2b6e-solver-position-reacquisition-20261008/report.md`.
