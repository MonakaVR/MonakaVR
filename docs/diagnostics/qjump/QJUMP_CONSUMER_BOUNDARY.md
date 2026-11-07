# Q-jump consumer boundary audit

## Scope / handoff

Task QJUMP-SOL-C; OFFLINE read-only audit, 2026-11-07. Requirement: the complete supplied `MonakaVR_QJump_ResidualTwist_捕捉診断_Offline_Orchestration_Codex指示_20261107.md`, particularly sections 4, 7–14 and 19. Root and repository AGENTS were reread and apply. The attachment's historical GPT-5.6 Sol role is being performed by this existing GPT-6.1 Sol / High agent as assigned by the parent; no model migration or additional spawn is claimed.

Parent G0 fixed repository `H:/tmp/6Dof/MonakaVR`, branch `fix/q-only-full-reset-20261106`, base/head `4cf9d14f42e62b4138b7c8c0fc8897bf1326ec13`, initially clean. Sole write scope in this audit: this document. No production/test/configuration edits, builds, tests, Git mutations, application operations, Reset or physical operation performed. SOFTWARE/PHYSICAL_HIL/CUTOVER: **NOT_RUN for this new diagnostic implementation**. Audit verdict: ACCEPT as implementation-design input; root design and implementation gates remain pending.

The earlier 73-case spike probe did not reproduce persistent consumer residue in its tested short software conditions. It is useful context, not evidence of long-term stability or proof that the real event began upstream. This task must capture the event rather than add Q rejection, heading correction, automatic Reset or solver changes.

## Existing normal product path

```text
MtpUdpReceiver worker / MtpInbox codec validation
 -> MtpObservationBackend server-thread admission/cache
 -> MtpPoseAdapter / ConstraintPipeline / ConstraintResolver
 -> pinned MonakaResolvedTickSnapshot
 -> MonakaServerIntegration.tick
 -> corrected/converged ResolvedTrackingPose -> ikConstraint
 -> MonakaSolverComposition -> one ConstraintIkWriteback
 -> SkeletonInputView private Tracker inputs
 -> HumanPoseManager.update / HumanSkeleton.readSolverRotation
 -> FK / constraints / positional IK when active
 -> bone and computed Tracker values
 -> MonakaServerIntegration.finishPoseUpdate before bridge dataWrite
```

The capture should attach to these same before/after hooks, using the same pinned tick, final selected poses and actual FK-read records. A fixture-only alternate input reader, another resolver call, or proxy storage read substituted for actual consumption is insufficient.

## Existing facilities and concrete gaps

| Facility | Already present | Gap / preservation rule |
| --- | --- | --- |
| `desktop/SameFrameDiagnostics.kt:309–398` before/after | Tick, pinned assignments, accepted MVIP wire metadata, selected owners, FK-read records, all bones/computed in detailed mode | Reuse scalar extraction and integration locations. New Q-jump profile must not alter existing schemas or existing Reset capture behavior. |
| `SameFrameDiagnostics.kt:417–445` inputTrackers | Actual admitted Q and raw/adjusted local Tracker evidence | Fixed CHEST selection; new capture must cover the five assigned Q bodies generically, not a producer-name list. |
| `SameFrameDiagnostics.kt:385` consumed inputs | Exact returned `Tracker.getRotation()` values and read sites | Existing `.take(16)` can truncate repeated spine reads before lower-leg records; Reset profile explicitly filters CHEST. New capture must retain all relevant selected-target reads under a declared bound and report truncation. |
| `SameFrameDiagnostics.kt:77–82` identity | Salted hash stable within one capture | Independent random salt prevents cross-repo equality joins; tests explicitly enforce this behavior. Do not reuse those hashes as cross-process causal keys or change the old privacy contract. Opt-in Q-jump bundle should record real structured public identities (or a parent-defined shared diagnostic token scheme). |
| Detailed `before` line 338 | Observation quality/freshness view | `pipeline.observations(now, assignment)` reruns eligibility, updating replay/freshness cursors. A new observation-only hook must use raw `observations()` plus pinned tick selection, explicitly label its basis, and never reevaluate admission for diagnostics. |
| `MtpObservationBackend.kt:32,96,309–320` Sample | Exact MtpPose, local sampleTime and receivedAtNanos | Existing SameFrame output omits local receipt time and accepted Q in its metadata block. Copy all three independently; sampleTime is derived, not original wire time. |
| `FullResetDiagnostics.kt` | Real Full STARTED/COMPLETED/FAILED, generation and System.nanoTime | Single observer slot. Do not install a second observer that replaces existing SameFrame recording. Fan out at the existing integration callback with independent failure isolation, or use the snapshot where event precision is unnecessary. |
| `ConstraintIkWriteback.kt:17–29,155–187` | Stable names, active/retained private objects, masks, actual topologyRebuilds | No detached proxy lifecycle snapshot exists. Expose only diagnostic immutable data from this owner. Dormant retained state must be distinguishable from active input. |
| `TrackerResetsHandler.kt:192–203` | correctionCalibrationEpoch from real reset/mount fields and reset counter | Record the exact opaque epoch; do not invent an upstream epoch from it. It excludes constraintFix. If individual interpretation transforms are required, add a read-only snapshot of the actual fields, including constraintFix, rather than guessing from orientation deltas. |
| `HumanSkeleton.kt:257–297` | Actual FK read recorder, one frame arming/drain | Begin/finish clear state. Two diagnostic consumers cannot independently drain the same frame; arm once and distribute one detached list. Preserve FK getter call count. |
| Existing writer | Bounded nonblocking offer, background serialization, failure counters, CREATE_NEW output | Useful behavioral pattern; existing full_reset_window is a one-shot 20Hz/5s+5s capture, not a 10s+10s rolling multi-trigger recorder. |

Default `correctConstraints=true` does not activate tracker correction: `HumanSkeleton.updatePose:659` calls `updateWithConstraints(false)` explicitly. That static fact can be included in a bundle's configuration context; diagnostics must not enable the disabled feedback path.

## Exact identity and time inventory

The following data already exists at the consumer boundary and can be copied without changing public input semantics:

- Public MVIP tuple: `publisher_id`, `source_id`, `tracker_id`; structured `LogicalTracker` and its existing observationId. Keep strings opaque. Do not parse a producer class from them.
- Envelope source generation: `session_id`, `clock_id`, `sequence`, `timestamp_ns`, `sent_at_ns`, `timestamp_kind`; mapping_revision and coordinate_space id/convention/revision.
- Existing `input`: source_id, device_id, session_id, sequence and orientation_evidence. Preserve their exact meanings. There is **no independent native/source timestamp in this consumer object**; missing upstream causal fields remain unavailable.
- Sample: `receivedAtNanos` is consumer runtime-clock receipt; `sampleTime = receivedAtNanos - (sent_at_ns - timestamp_ns)` is a derived local time. It must not replace remote timestamps or be called a physical capture timestamp.
- Pinned consumer frame: tickSequence, nowNanos/resolvedAtNanos, assignment snapshot/generation, paused flag and the separately labelled trackerReceiptCutoffSystemNanos.
- Real Full Reset timeline: System.nanoTime-domain values. These share a process domain with the existing `system_ns`, not with arbitrary producer monotonic clocks.

New diagnostic metadata may identify the recorder itself: schema version, bundle ID, event ID, process-run ID, local observation ordinal and clock-domain labels. These are **diagnostic identifiers**, not fabricated native sequence/session/pose fields. Any new per-proxy instance ordinal must be labelled process-local diagnostic observation of the actual object.

All sequences, revisions and nanosecond integers should be JSON decimal strings end-to-end (fixtures already contain values beyond 2^53). Never round-trip through float, JavaScript Number or formatted seconds. Add a UTC clock anchor pair per sampled frame/event, with before/after monotonic observations or a documented sampling uncertainty; preserve original wire and receipt times separately. Different process monotonic phases are never directly comparable, even when numbers or units look similar.

## Minimal hook candidates for parent design

These are proposed edits only; none were made in this audit.

1. New opt-in `QJumpDiagnostics` recorder and small configuration adapter in desktop, default OFF. It owns bounded ring/trigger state, worker/queue and consumer-owned bundle directory. Main's existing integration factory supplies it; no producer discovery or private files/processes.
2. A detached lifecycle snapshot from `ConstraintIkWriteback`, reachable through its existing sole `MonakaSolverComposition` owner: target; stable name; active/dormant; hasPosition/hasRotation/status; exact object instance token; relation Q/6DoF selection and useAsIkConstraint/outputMode; assignment generation; current topologyRebuilds; raw proxy Q; existing reset/correction epoch. Copy scalar values on the server thread. Background code receives no mutable Tracker/skeleton reference.
3. Use actual object creation/reuse for an optional process-local instance ordinal. It remains unchanged across same-object loss/recovery and changes on replacement. An object token is not a new protocol generation. Avoid unbounded identity maps; prune with current bindings, or use weak keys with bounded active metadata.
4. One server-thread FK recording coordinator in MonakaServerIntegration: sample if old SameFrame or new Q-jump needs it; arm exactly once before manager update; finish once after update; feed the detached result to both sinks. Existing SameFrame methods can accept an optional already-collected list while retaining old standalone defaults. Do not add extra `getRotation()` calls as a consumption surrogate.
5. Preserve existing FullResetDiagnostics observer ownership: the existing integration registration can call both old/new sinks under separate `runCatching`/diagnostic guards. Close both without waiting on the tracking thread. Snapshot polling of proxy correction epoch additionally observes Yaw/Mount/reset changes that do not advance the Full-only generation.

Each 5-tracker frame should contain the exact assigned tuple/Q-source/6DoF-source, admitted original Q and validity, resolved owner/Q, private lifecycle snapshot, actual consumed Q/read-site/masks, relevant bones and computed trackers, and Full/reset/assignment/topology context. It should explicitly mark an unavailable/stale/unassigned sample and missing FK reads rather than reuse the last Q as a current observation. Record the selected-body list and any truncation.

For interpretation: compare admitted to consumed through the observed reset state, **not simple equality after a valid reset**. Bone/computed mapping is target-specific and constrained, not an expected identity transform. Multiple consumed reads for one Tracker may feed several bones; retain read site and target.

## Bounded capture / trigger proposal

Parent will fix the shared constants and coordination schema. A suitable consumer starting point is 50Hz sampling, 10s pre + 10s post, with a strict frame count, selected-target count, per-frame field/string limits, pending event count, event bytes and total retained event count. At 50Hz, pre history is about 501 frames; one full window about 1001 plus trigger/manifest records. An explicit per-event byte cap must accompany the count bounds; JSON size alone does not bound nested object memory unless field counts and string lengths are also bounded.

Normal disk activity should be absent until a trigger or shared request. Copy immutable frame data into the server-owned ring; queue frozen bounded event work without waiting. Serialize/write on a worker, use CREATE_NEW/atomic completion metadata, and retain complete-record-only failure summaries. Queue overflow, truncation, age/window shortfall, serialization failure and partial windows are evidence-quality flags, never tracking failures.

Triggers use sign-invariant `2*acos(clamp(abs(dot(normalize(q1),normalize(q2))),0,1))`, with explicit finite/zero-norm handling. The diagnostic threshold (initial 45 degrees) changes no input acceptance. Step continuity requires the same real identity/session/clock/context; lifecycle changes are their own events rather than motion deltas. Persistent-offset candidate means a remembered pre-step anchor remains far away after >=2s of local process time, not that drift or fault has been proven. Invalid/stale gaps must be represented, not interpolated into proof.

Include separate triggers/observations for Full generation, correction epoch, assignment/Q/6DoF relation, proxy instance/name/topology, sender session/clock and space/mapping revision. The cold initial binding should be distinguished from an unexpected replacement. Sample-rate detection can miss spikes shorter than its interval; the bundle must declare this resolution. If parent requires every accepted transient to trigger, add only a bounded lightweight detector at that existing acceptance/read boundary and keep rich snapshots sampled; do not claim 50Hz capture proves absence of faster events.

## Shared incident request and silent participants

Consumer-only triggering cannot ensure an upstream participant with stable Q emits its window. The parent-defined generic opt-in diagnostic request must be polled independently of pose arrival, by a background worker, so a silent/unaffected process can freeze its pre-ring, continue its post-window (including no-new-sample markers), and acknowledge the same incident request. Filesystem polling/reads/writes do not belong in the server tick.

The consumer may access only this explicitly configured **generic diagnostic coordination** surface and its own output, not producer-owned configuration, executables, private SDKs, logs or lifecycle state. Offline analyzer input bundles are separate explicit analysis inputs. Requests cannot command Reset, calibration, assignment, producer recovery or process lifecycle. The owner queue passes only bounded diagnostic requests to the server; unavailable participants/timeouts become incomplete incident evidence.

Coalescing/repeated triggers and pending requests require explicit bounded policy. Record origin event/request IDs, acknowledgement, local receipt UTC/process times, selected window and completeness; do not silently merge different incidents by nearby filenames or overwrite old evidence.

## Analyzer requirements / boundary limits

The analyzer is offline, accepts supplied bundles and writes analysis JSON/Markdown. It must validate schema, repo/product/role, build/source identity, bundle/process-run/event IDs, completeness and sequence/time ranges before classifying a boundary divergence.

- Use exact opaque identities and session/sequence relations that actually cross each boundary, plus a narrow UTC window and documented clock uncertainty. Different repos may have different envelope sequences; use explicit `input.*` links where genuinely present. Never guess that counters match or reconstruct a native key absent from MVIP.
- Same event_id alone is insufficient when bundles are stale, outside the requested wall window or from the wrong process run/source. Conversely missing event_id/shared acknowledgement must not be replaced by a fabricated correlation.
- Preserve phase/boundary labels: native output, Bridge normalized/published, consumer admitted, resolved, actual FK read, post-solve bone/computed and pre-output hook are different observations. Equal timestamps do not make them the same measurement.
- Detect sign flips as the same pose. Normalize only in analysis/detection math; retain original stored quaternion values.
- Admit CASE A–D only as supported candidates after matching chain coverage. Lifecycle near onset is CASE E / CORRELATED_NOT_PROVEN, not causal proof. Missing identity link, silent source without a saved bundle, truncated actual reads, dropped frames or mismatched windows yields **INSUFFICIENT_EVIDENCE**, not a chosen cause layer.
- A stable upstream and changed consumed Q can be an intentional Reset. Require reset/assignment/proxy context before labeling an interpretation divergence. A lasting computed residual with recovered consumed Q is only a solver-state candidate, not proof of irreversible IK corruption.

## D1–D11 and real-path E2E acceptance plan

No tests were executed for this new task. Proposed focused tests use deterministic injected diagnostic clocks and sinks, real public codec input, and actual manager update where specified; diagnostic-off numerical parity is a required negative control.

| ID | Required evidence |
| --- | --- |
| D1 | Normal below-threshold motion updates bounded pre-ring, creates no event/disk writes, and leaves real consumed/bone/computed behavior identical to diagnostics OFF. |
| D2 | q -> -q -> q creates no angular trigger; lifecycle and exact stored signs remain observable. |
| D3 | Same identity/session 120-degree valid step emits one correctly labelled candidate and preserves source sequence/timestamps. |
| D4 | 180-degree step works without acos/domain/nonfinite failure; q sign equivalence remains intact. |
| D5 | Step -> recovery saves exact pre/post frames and actual FK reads, including 5 selected bodies; no stale observation presented as current. |
| D6 | Synthetic step held past 2s yields persistent candidate against its pre-anchor; normal motion/invalid gap/lifecycle cases are not treated as proven drift. |
| D7 | Real HumanPoseManager Full Reset delivers original SameFrame STARTED/COMPLETED and new diagnostic events together; correction epoch/Yaw/Mount changes captured without changing transform behavior. |
| D8 | Real assignment API mutation records pinned old/new generation and exact Q/6DoF relations, never an assignment inferred from incoming metadata. |
| D9 | Real loss/recovery same-object case differs from genuine source replacement; proxy instance token and topology counter follow actual writeback lifecycle. |
| D10 | Public codec/backend session, clock, mapping/space revision and identity transitions are visible with correct unavailable intervals; no counter guessed across epochs. |
| D11 | Five targets at selected rate, bounded frames/bytes/pending requests; blocked/failing writer and request coordination cannot block tick/close or kill tracking; dropped/incomplete flags are exact. |

Real-path E2E should extend existing `SolverCompositionIntegrationTests` fixture: five distinct conforming public tracker tuples -> inbox/backend -> production MonakaServerIntegration before hook -> real HumanPoseManager -> after hook -> background bundle. Verify all five actual target reads survive the old 16-record hazard, P=false/Q=true, numerical output parity OFF/ON, reset/event fanout, and exact original metadata above 2^53. Include local positional hands when claiming active positional CCD, otherwise label FK-only/root-position case accurately.

A separate local multi-participant E2E must submit the generic request while the synthetic upstream participant has no new pose traffic. Require its saved window/ack or explicitly incomplete status; analyzer must reject wrong bundle identity/session/sequence/window, missing upstream links and cross-clock phase equivalence. Test CASE A–E fixtures with independently authored trajectories/metadata and negative mismatches, not analyzer output copied from implementation assumptions. Existing `SameFrameDiagnosticsTests`, `FullResetDiagnosticsTests` and public-input FK tests provide preservation checks; do not rerun unrelated all-repo suites for small diagnostic edits.

## Proposed file ownership / pending decisions

After parent design approval, sole Monaka production/analyzer owner remains this Sol-C agent. Likely scope: new desktop Q-jump recorder/config; small integration/Main opt-in hook; detached core writeback/composition snapshot; minimal actual-read fanout; optionally a reset-state snapshot; `tools/analyze_qjump_incident.py`; focused tests. No concurrent production owner in this repo. Existing Full Reset diagnostics and prior artifacts must remain intact.

Parent must still fix shared event/request/bundle schema and coordinator ownership, rate/caps/coalescing/retention, exact identity policy, per-boundary source linkage and incomplete-evidence rules. This audit does not authorize implementation, runtime build, commit or HIL startup. Final readiness remains **NOT_RUN**, not READY_FOR_QJUMP_CAPTURE.
