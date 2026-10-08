# Phase 2B-6L — Production Composition / Writeback Ownership Foundation

Production solver composition and exclusive writeback ownership are implemented
in core `MonakaSolverComposition`. The generic production route is ACTIVE.
Production Position Correction is NOT ENABLED. OpenVR HMD remains POSE_ONLY,
Strong Trusted UNSUPPORTED, Production Raw HMD BLOCKED BY BACKEND, complete
predictor sources NOT AVAILABLE, 2B-5P NOT READY and 5S physical HIL pending.

## One owner, one boundary, exclusive reservation

Integration owns the composition lifecycle, with no direct writeback field or
call. Composition constructs exactly one private `ConstraintIkWriteback` shared
by generic apply and the internal future 6G typed sink. Stable private proxy names,
IK calibration and topology survive owner changes; capability changes still
follow the existing topology rules. The internal PC API and all 6G/6J/6K types
remain internal. No test-only production enable flag or desktop dependency is added.

On the owning server thread, preflight checks close/sequence state, then reserves
the production sequence before pause disposal, session creation or any processing
or solver side effect. Duplicate and rollback reject without additional effects;
gaps and new sequences with identical now are legal. Exceptions keep reservations.
Generic writeback uses existing apply and the pinned assignment without duplicating
solver logic. Receipts record sequence, owner and writebackAttempted for diagnostics.

The internal PC seam checks sequence, now, resolvedAt, full assignment and resolver
map coherence before reservation. It lazily creates an exclusively owned session
around 6G with the same writeback. Once PC is selected there is no generic commit
or fallback, even after 6G preflight rejection (zero writes), exception, or partial
typed receipt. Either-owner same-tick retry and rollback are prohibited. Coherence
failure is preflight, before owner selection. Closed composition rejects both APIs.

The 6G MAIN_DIRECT contract preserves TRACKER_ORIGIN through its typed sink;
learned fallback uses IK_EFFECTIVE_TARGET with existing calibration compensation.
Tests use generic/PC/generic modes and corrected fallback with nonzero calibration:
the private proxy is shared, calibration remains equal, and unchanged masks cause
no extra topology rebuild. Production numerical tuning and source trust are unchanged.

## Construction-time configuration and Raw HMD gate

Null config means NOT_CONFIGURED, no foundation, Non-HMD adapter or session factory.
Present config means CONFIGURED_RAW_HMD_BLOCKED, with validated immutable foundation
and dormant adapter/factory. Neither construction nor generic commit captures live
trackers/body geometry or creates a session. Config presence does not mean runtime
activation. There is no ACTIVE production gate. Hot reload is unsupported: restart
or rebuild composition to change config. Foundation preparation precedes attaching
writeback, avoiding leaked skeleton views on materialization failure.

Production calls neither source snapshot capture, 6K capture nor 6G process. Core
never probes desktop HMD admission, constructs a production RawHmdPoseInput, or
assembles complete predictor sources. Generic/HMD-blocked provider read count is
zero beyond the existing runtime backend polls. MonakaConfiguration is unchanged.

## Pinned solver tick and post-IK frame

Integration calls runtime.tickSnapshot once and retains its tickSequence, now,
paused fact, assignment and constraints through commit and post-IK processing.
resolvedTrackingPoses(tick) wraps tick.constraints and reads diagnostic metadata
through pipeline.observations(tick.nowNanos, tick.assignment). It neither re-resolves
constraints nor snapshots live assignments. The legacy overload remains for other
callers. Metadata must be read before later polling replaces pipeline state; this
API is not a historical observation store.

HIP Main/fallback IDs and generation use tick.assignment. Phase 1 rotation correction
uses tick.now and derives one component from the raw pinned pose map, fanning it
out to existing IK/visible paths. Generic commit receives the existing ikConstraint
map and uses tick.assignment unchanged. No current solver/frame path reads live
lastTickNanos or re-snapshots assignments. Visible output strategy changes still
require restart, checked against the captured tick.

PendingPoseFrame holds the entire tick plus resolved poses and raw diagnostic facts.
finishPoseUpdate consumes it once, using pending now/paused for background IK,
continuity, output and diagnostic ages. Double after hook is a no-op; an unconsumed
frame at the next before hook fails closed and tears down instead of overwriting.
Close clears the pending frame and both hooks. Normal VRServer before/update/after
ordering runs on one thread, preserving existing pause behavior; later live state
cannot retroactively change the captured frame. The numeric Direct/Hybrid and
Phase 1 rotation correction paths are preserved.

## Pause, resume and close ownership

Pause edges use accepted reserved tick.paused, never a second live skeleton read.
Entering pause drops/disposes the future session, clearing its learner and continuity
together before generic apply. Repeated paused ticks create no session. Existing
paused writeback semantics keep proxies unchanged. Internal paused PC requests
reserve and return PAUSED without process/writeback. Resume alone creates nothing;
the next future request creates a new orchestrator and cannot reuse old state.
Fresh Raw HMD/Raw IMU admission is deferred to activation and remains mandatory;
resume is not permission to activate. 6C/6E assignment lineage remains fail-closed.

Composition close marks closed, drops session and closes shared writeback in finally.
Close is idempotent. Startup/failure cleanup covers receiver, direct output,
composition/writeback, runtime and hooks even when a registration/teardown callback
throws. Tests cover construction failure at allocation, output registration and
both hook registrations, port release and skeleton view restoration.

## Future HMD prerequisite and validation

Future activation requires authoritative desktop Trusted Raw HMD admission, immutable
RawHmdPoseInput, then a separately audited core active gate. Phase 1 rotation correction
ordering relative to PC resolved constraints must be re-audited then; 6L internal
tests use existing 6G fixture semantics only.

Next phase selected: **5S Physical HIL Return**. No new backend evidence was added
beyond POSE_ONLY/UNSUPPORTED; re-running the same software evidence audit would not
remove this blocker. Physical/source evidence is needed before reconsidering HMD
activation. HIL for 6L: NOT REQUIRED / NOT RUN.

Validation and Git receipts are recorded in
build/reports/phase2b6l-production-composition-writeback-20261008/report.md.

## 6L validation receipt (2026-10-08, Asia/Tokyo)

PASS — PRODUCTION COMPOSITION / WRITEBACK OWNERSHIP FOUNDATION IMPLEMENTED.
Core full: 1,754 tests, zero failures/errors/skips. Desktop full: 142 tests,
zero failures/errors/skips. Composition: 22 tests; integration ownership: 11;
Hybrid integration: 4; tick snapshot: 18; 6K Non-HMD: 30; 6G orchestrator: 45;
solver reference semantics: 41. shadowJar PASS. MTP process E2E: 11 stages PASS.
Full suite counts and log/evidence locations are recorded in the local build report.
HIL NOT REQUIRED / NOT RUN. Protected patch and existing untracked architecture
note retain their original SHA256. Forward commit and normal push receipts are
recorded in the build report after commit/push; no history rewrite is performed.
