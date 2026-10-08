# Phase 2B-6I — Production Tick Snapshot Foundation

`MonakaRuntime.tickSnapshot(paused)` captures one production tick as a
`MonakaResolvedTickSnapshot`: `tickSequence`, `nowNanos`, `resolvedAtNanos`,
`paused`, the captured `TrackerBodyAssignments.Snapshot`, and frozen `constraints`.
This boundary is available to future integration; Position Correction remains
dormant/injected and is not invoked by MonakaRuntime.

## Tick contract

On the existing server thread, the runtime checks that it is open, reserves a
runtime-local sequence, reads its clock once, rejects a negative clock value,
then captures the assignment registry once before polling. The first sequence
is zero. Every tick, including a paused tick or a legacy `tick()` call, reserves
a new sequence even when the clock value is unchanged. Clock/unexpected failures
consume the reservation; gaps are legal. Long.MAX_VALUE may be reserved once;
subsequent calls fail before reading the clock. Sequences never wrap and have no
relationship to MTP/IMU/physical sample sequence identity.

Slime and MTP implement the optional assignment-aware backend seam. The runner
passes the same captured object to both, using the ordinary poll method for
other backends. Source ownership, ingestion, explicit removal and authoritative
snapshot removal share the existing runner implementation. The backend ID list
is captured before polling, so newly registered backends start on the next tick.

Slime selects explicit targets from the supplied targets map. Existing legacy
tracker body-position fallback remains unchanged. Its legacy poll reads its
assignment provider once before evaluating or iterating trackers. MTP compares
its stored assignment generation with the **supplied** generation, and rebinds
cached devices only when that generation changes. Rebinding retains original
sample time and provenance; tick now never restamps a physical sample.

Pipeline explicit `resolve(target, now, assignment)` and
`resolveAll(now, assignment)` bypass the resolver's legacy assignment provider.
The runtime's optional assigned-IMU freshness filter also receives the pinned
assignment, including its generation. Legacy resolver/pipeline APIs remain
available. The set of resolved targets continues to come from the observation
store, preserving the existing map shape when an assigned target has no samples.

Assignment changes after capture cannot affect this tick's poll/selection.
They become visible on the next tick without a global lock on assignment writers.
Registry publications copy and wrap the targets map as unmodifiable, including
the initial generation. Generation overflow fails before publishing a new state.
The captured registry Snapshot itself is retained in the tick result.

The resolved map is copied and wrapped as unmodifiable. EffectiveConstraint and
ResolvedComponent contain only immutable fields, and Vector3/Quaternion are
immutable value classes, so copying components adds no protection. Holding an
old tick across new observations, reassignment or pipeline clear does not change
its values. The snapshot constructor validates non-negative sequence/time and
assignment generation, and requires `resolvedAtNanos == nowNanos`.

## Compatibility and scope

`tick(paused)` is a thin `tickSnapshot(paused).constraints` wrapper. The snapshot
path is the sole tick implementation. `lastTickNanos` retains its existing early
update position after a valid clock read; negative clock values fail before
poll/resolve and leave the last valid time unchanged. Successful ticks always
agree with snapshot now. The runtime continues to require server-thread tick
ownership; this API does not add concurrent or reentrant tick scheduling.

MTP pause edges still suspend and invalidate sources. Paused traffic advances
watermarks. Resume fully admits the bounded backlog while still suspended,
discards samples, and requires a newer sequence after resume. A failed backend
invalidates only its sources, invalidates MTP samples where applicable, increments
diagnostics, and allows peer backends to continue.

MonakaConfiguration and MonakaServerIntegration are unchanged. Production still
uses the existing generic writeback owner. No Position Correction invocation,
Raw HMD/IMU/Main teacher/body model adapter connection, calibration persistence,
correction pause/resume lifecycle or output behavior is added here.

## Validation and next phase

Deterministic tests cover runtime-wide poll/resolve pinning, assignment changes
during tracker iteration and eligibility evaluation, explicit MTP generation
rebinding without new samples, legacy compatibility, clock/sequence failures,
immutable maps, pause/resume facts and backend failure isolation. Existing
provenance/freshness, solver, output and configuration regressions remain gates.
Executed counts and Git receipts are recorded in
`build/reports/phase2b6i-production-tick-snapshot-20261008/report.md`.

2026-10-08 software validation: dedicated runtime/backend/pipeline tests 61 PASS
(17 new snapshot tests), full Core 1388 PASS, Desktop 131 PASS, shadowJar PASS,
MTP process E2E all 11 stages PASS. Both full test suites have zero failures,
errors or skipped tests. Production configuration/writeback source is unchanged.

Production Position Correction: NOT ENABLED. Orchestrator: DORMANT / INJECTED only.
Raw HMD: BLOCKED BY BACKEND. Raw IMU, Main teacher, Body Model, Main mount and Fixed
calibration: NOT CONNECTED. HIL: NOT REQUIRED / NOT RUN.

Next: **Phase 2B-6J — Position Correction Configuration / Calibration Foundation**.
Establish explicit opt-in, Raw IMU space confirmation, Main mount and predictor
fixed calibration persistence/acquisition, and versioned predictor/pairing/
learning/reacquisition policy before connecting production adapters.

## Phase 2B-6J configuration follow-up

[6J configuration/calibration foundation](position-correction-phase2b6j-configuration-calibration.md)
adds schema v3 explicit opt-in persistence and pure validated runtime-policy
materialization. v1/v2 and absent/disabled v3 remain Position Correction opt-out.
All calibration/space/policy inputs are mandatory with exact source/body/HMD/session
binding; no defaults or identity/zero inference. This supersedes earlier statements
that configuration/calibration persistence is unimplemented. Production invocation,
live source acquisition and writeback ownership remain unchanged and disconnected.
Raw HMD remains BLOCKED BY BACKEND. Next is 6K Non-HMD Production Input Adapter
Foundation; HIL for 6J is NOT REQUIRED / NOT RUN.
