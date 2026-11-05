# Q-only Full Reset fix design

Scope: consumer-owned reset interpretation for explicit MVIP Q-only IK input. Base `85a9ead351abec6f56de37e1f854bbe96801ae33`; topic `fix/q-only-full-reset-20261106`.

## Product boundary

Keep accepted MVIP samples, source selection, resolver values, wire, FK/IK algorithms, Direct output and existing observation boundaries unchanged. Modify the private solver-input adapter and its existing accepted-tick owner only as needed. Production edits have one owner.

An eligible relation has an explicit source selection, `sixDofSource == null`, an MVIP `qSource`, and IK participation. Use the structured reference, never vendor strings or transient P validity. Slime Q, implicit legacy Main, all SixDoF assignments and disabled IK participation retain the old path.

## Existing reset semantics

- Create a normal, non-internal/non-computed private rotation Tracker with `allowReset=true`, `allowMounting=true`, filtering disabled as before, and initial existing `mountingOrientation=IDENTITY`.
- Identity here means no additional consumer mount has yet been selected. Leaving the handler's default half-turn would change admitted Q even before a user reset.
- Keep the existing `FeedbackExclusion.registerOwnedOutput` guard and private `SkeletonInputView`; never register the proxy as raw input.
- Full/Yaw/Mount operations act through the existing skeleton and TrackerResetsHandler. Do not duplicate their quaternion formulas.
- Reuse applicable consumer reset settings. Private mounting state remains in memory; do not persist fabricated private Tracker entries through `saveMountingResetOrientation`. The ordinary raw Tracker persistence path remains untouched.
- Advance only existing private yaw-reset smoothing from the monotonic time of an accepted composition tick. Rejected/duplicate ticks must not advance it twice. Do not poll providers, call raw `Tracker.tick` for unrelated side effects, or redefine freshness.

## Lifecycle

Separate the active proxy set from a bounded retained reset-aware set, at most one per currently eligible body/Q relationship. Invalid/lost/stale Q removes the active constraint and all FK consumption, while retaining that relationship's exact Tracker/reset handler. Same identity recovery and topology/assignment-generation refresh reuse it. A genuine Q-source/body/mode change, unassignment, disabled IK participation or adapter close discards the corresponding state. Do not copy old offsets to a different source. Reset while an input is absent must not reset a stale dormant sample.

Process restart is outside this in-memory retention contract; perform the normal user reset after restart. No new persistence format is introduced.

## Acceptance

T1 normal reset baseline; T2 public MVIP Q-only Full Reset; T3 actual consumed P=false/Q=true and corrected Q; T4 real skeleton/computed orientation toward the existing head-yaw reference; T5 input P-only changes remain invisible; T6 post-reset Q still drives the model; T7 two current-pose Full Resets without double offsets; T8 existing and private Yaw/Mount semantics; T9 same-relation rebuild/generation/reconnect retention and different-source negative control; T10 valid/lost/recovered Q with no stale consumption.

Use ordinary Tracker behavior and an independent head-yaw expected orientation as oracles, plus actual FK records and real computed results. At least one failing test must first demonstrate the base defect. Keep current P/Q continuity and non-Q-only behavior covered by relevant regression classes. No hardware or all-repository audit.

After independent review and root G2 acceptance: local topic commit, one normal `Build_Runtime.cmd --offline`, standard provenance verification, and HIL handoff. Rollback is the recorded base plus its normal build when authorized; do not create dated binary copies. No push, dev/main merge, runtime startup or HIL in this task.
