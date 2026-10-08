# Phase 2B-6K — Non-HMD Production Input Adapter Foundation

6K binds non-HMD production inputs to the completed 6I tick, using 6J's explicit
validated configuration/calibration. The core adapter is READY / DORMANT. It has
no production caller, learner, orchestrator, solver or writeback ownership.

## Tick and physical receipt cutoff

The old 5Y capture computed receipt age at adaptation time and subtracted it from
an earlier runtime `now`. A physical sample arriving after `now` could therefore
be backdated into that tick. The new tick-aware boundary closes this race.

`MonakaRuntime.tickSnapshot()` reserves its sequence, captures
`trackerReceiptClock()` once, then reads the runtime clock once, then pins
assignment and polls/resolves. `trackerReceiptCutoffSystemNanos` is an opaque
System.nanoTime-domain value, comparable with `Tracker.OrientationSample` receipt;
its absolute sign is not a validity signal. Runtime `nowNanos` is non-negative in
the separate runtime monotonic domain. No clock conversion uses absolute values.

`captureAtTick` holds the existing reset/Tracker monitors and retains sample,
source-epoch and calibration-epoch stable-read checks. A sample receipt greater
than cutoff returns `SAMPLE_AFTER_TICK_CUTOFF` before any cache lookup or mapping.
That reason propagates through the raw boundary and the Non-HMD component result.
A sample exactly at cutoff is eligible. Samples between cutoff and runtime `now`
are conservatively deferred too. A later tick may accept the sample.

For the first accepted sequence, `ageAtCutoff = subtractExact(cutoff, receipt)` and
`sampleAt = runtimeNow - ageAtCutoff`. Overflow, negative age or age greater than
runtime `now` returns `SAMPLE_TIME_UNAVAILABLE`; nothing is clamped. An accepted
sequence's first runtime mapping is reused on subsequent captures. Legacy capture
remains for existing observation/tests, while this adapter calls only `adaptAtTick`.
Its supplied-cutoff path reads no clock.

## Raw sources and independent context

After a tick completes, `captureSourceSnapshot(tick)` requires the exact latest
completed snapshot from that runtime, including its time anchor. Old, forged,
closed-runtime and failed-new-tick capture requests fail closed. The server-thread
seam reads neither clock nor live assignment registry. It uses
`pipeline.observations(tick.nowNanos, tick.assignment)`, runner owner state, and MTP
backend current contexts. All maps and source assignment targets are copied and
unmodifiable. Subsequent poll/removal/reassignment cannot mutate old snapshots.

`MtpObservationBackend` owns a separate context per logical tracker, updated only
after its existing feedback/session/peer/sequence/space/mapping/absence/suspension/
timestamp admission accepts a pose. The semantics match `MtpPoseAdapter` exactly:

| Field | Owner value |
|---|---|
| logical identity | publisher/source/tracker tuple |
| source epoch | `${pose.session_id}:${pose.clock_id}` |
| calibration epoch | `pose.input.session_id` |
| mapping revision | `pose.mapping_revision` |
| space | `pose.coordinate_space` |

Context clears with sample invalidation, accepted session change, clock/space
mismatch, mapping advance before a new pose, source removal, applicable absent/
lost removal, suspension, close, timestamp admission failure that replaces a
sample, and Float adaptation failure. Existing admission/wire rules are unchanged.
Duplicate/peer-rejected traffic cannot refresh it. Freshness-only STALE does not
erase current context; stale raw quality still prevents teacher availability.

## Main teacher and expected epoch

The sole Main authority is the tick HIP assignment. Capture requires its typed
MTP identity, the exact raw observation source, runner ownership `owner == "mtp"`,
and a backend context with the same logical identity and configured exact space.
A source ID with an MTP prefix supplies no ownership proof. EffectiveConstraint,
corrected/reacquired positions and computed/output/IK poses are never teachers.

Configured Main mount source must equal current assigned Main source; drift
returns typed unavailable, with no automatic binding update. Geometry uses only
`MainTrackerMountToHipCenter.normalize`: the raw sample's tracker rotation applies
the configured tracker-local offset to its position, yielding HIP_CENTER.

Expected `PositionTeacherEpoch` is constructed from backend context, pinned tick
assignment generation, HIP_CENTER, and configured mount identity. Teacher
provenance is then compared against that independent epoch. `PositionTeacherEpoch.from`
is not used to manufacture expected authority. Old teacher/new context fails
closed even when normalization succeeds.

## Physical tracker, live Body Model and Fixed Calibration

The assigned HIP rotation fallback must exactly match configured raw IMU source.
Slime persistent name lookup uses exact equality. Missing and duplicate matches
return `FALLBACK_TRACKER_NOT_FOUND` / `FALLBACK_TRACKER_AMBIGUOUS`; no first-match
selection. The existing raw boundary alone validates physical/IMU/internal/
computed/HMD/target/status/rotation/space/sample eligibility.

`HumanPoseManager.currentHipBodyModelSnapshot()` is a read-only ThreadSafe facade
over the existing atomic publication. Capture reads `bodyModelSource()` once and
`trackers()` once, snapshotting the iterable. It never reads six mutable offsets,
reconstructs solved geometry or defaults an unavailable body. No singleton server
or global config is acquired.

Fixed calibration is exactly the configured snapshot. Available live body requires
`fixedCalibration.bodyModelId == body.identity.modelId`; mismatch is typed
unavailable without auto-rebind or live regeneration. Body unavailability propagates
its reason to Fixed availability. Body geometry epoch changes are retained in the
new bundle and do not mutate an old bundle. Configured HMD source binding remains
recorded; live HMD verification belongs to future composition.

## Bundle and production status

Fatal preflight checks tick sequence, time, assignment generation and full
assignment content before reading providers. `PositionCorrectionNonHmdProductionTick`
holds immutable tick/time/assignment-generation/paused facts and independent Raw
IMU, teacher, body and Fixed results. A missing teacher does not discard a healthy
IMU/body/Fixed; an IMU failure does not discard a teacher.

| Component | Status after 6K |
|---|---|
| Production tick/config foundation | READY |
| Non-HMD capture / cutoff Raw IMU / calibrated Main teacher | READY / DORMANT |
| Expected teacher context / live body / Fixed compatibility | READY / DORMANT |
| Raw HMD | BLOCKED BY BACKEND |
| Complete predictor sources | NOT AVAILABLE |
| Production orchestrator caller | NONE |
| Typed writeback handoff | NOT DONE |
| Production Position Correction | NOT ENABLED |

There is no Raw HMD field, nullable placeholder, synthetic HMD, complete
PredictorSources assembly, orchestrator invocation or downstream writeback
dependency in 6K. MonakaServerIntegration ownership and MonakaConfiguration are
unchanged. OpenVR HMD remains POSE_ONLY, Strong Trusted UNSUPPORTED, 2B-5P NOT READY,
and 5S HIL pending. Pause/resume learner/continuity hard reset is deferred to
production composition. HIL: NOT REQUIRED / NOT RUN.

Validation receipts are in
`build/reports/phase2b6k-non-hmd-production-inputs-20261008/report.md`, including
dedicated tests, full Core/Desktop, shadowJar, MTP process E2E and Git/source
preservation. Next: Phase 2B-6L — Production Composition / Writeback Ownership
Foundation, to establish one core facade and exclusive future handoff while Raw
HMD remains blocked and full production enablement remains deferred.
