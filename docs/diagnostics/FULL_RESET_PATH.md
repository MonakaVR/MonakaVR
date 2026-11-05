# Full Reset observation path

Scope: OFFLINE observation only, based on MonakaVR
`0c8ae3da210880204d93b4b29f1523f7255ae98f`. The reset algorithm,
source selection, mounting/body offsets, FK/IK inputs and output routing are
unchanged. This document does not certify physical Reset behavior or SteamVR.

## Product request and execution

Repository-relative source paths below identify the existing product path:

| Stage | Source and behavior |
| --- | --- |
| UI | The operation used here is the lower-right `ResetButton type={ResetType.Full}` in `gui/src/components/onboarding/pages/body-proportions/ScaledProportions.tsx`. `gui/src/components/Toolbar.tsx` also provides a Full `BasicResetButton` using `useReset`; `components/home/ResetButton.tsx` shares the hook. |
| Request | `gui/src/hooks/reset.ts`, `triggerReset`: creates SolarXR `ResetRequestT`, sets `resetType = ResetType.Full`, sends `RpcMessage.ResetRequest`. This is a request, not proof of execution. |
| RPC | `server/core/src/main/java/dev/slimevr/protocol/rpc/reset/RPCResetHandler.kt`, `onResetRequest`: Full branch reads explicit/configured delay and calls `scheduleResetTrackersFull`. |
| Delay | `server/core/src/main/java/dev/slimevr/reset/ResetTimer.kt`: timer completion executes the callback; zero delay invokes it immediately. Countdown STARTED responses are not actual Full Reset execution events. |
| Server ownership | `VRServer.kt`, `scheduleResetTrackersFull`: completion calls `queueTask`. `VRServer.run`, annotated `@VRServerThread`, drains tasks before bridge reads, tracker ticks, before-pose hook, pose update and after-pose hook. Direct `VRServer.resetTrackersFull` also queues the same manager call. |
| Manager | `tracking/processor/HumanPoseManager.kt`, `resetTrackersFull`: calls `skeleton.resetTrackersFull`, then existing OSC yaw alignment, VMC alignment and drift logging. |
| Actual Full Reset | `tracking/processor/skeleton/HumanSkeleton.kt`, `resetTrackersFull`: existing head/reference handling and eligible `trackersToReset` call `TrackerResetsHandler.resetFull`; then floor/buffer, localizer and IK offset reset requests, ending with the existing reset log. |

The diagnostic owner is **`HUMAN_SKELETON_FULL_RESET`**. STARTED is emitted
at entry to this actual skeleton operation; COMPLETED follows its existing
body, or FAILED records an exceptional exit. This boundary includes reset
requests made inside that operation. Some effects, such as floor recomputation
and solver offset application, happen in a later update. It excludes subsequent
`HumanPoseManager` OSC/VMC alignment and drift logging; COMPLETED does not claim
those downstream operations or a rendered frame completed.

The base had per-tracker `TrackerResetsHandler.lastResetQuaternion` and reset
drift history. It had no process-wide diagnostic Full Reset generation or
same-frame execution timestamp. `ResetHandler.sendStarted/sendFinished` are
listener notifications; countdown STARTED is not the new execution boundary.

## Execution records and frame linkage

`monaka.full_reset.v1` records contain:

```text
event = FULL_RESET
owner = HUMAN_SKELETON_FULL_RESET
phase = STARTED | COMPLETED | FAILED
reset_type = FULL
reset_generation (decimal string)
system_ns, started_system_ns, completed_system_ns (decimal strings or null)
```

Each actual Full operation advances the process-local diagnostic generation.
Yaw and mounting resets do not become FULL events. The counter and timestamps
are diagnostic state, not tracking, validity, selection or persistent settings.

`monaka.same_frame.v1` retains its consumer tick and adds `reset_generation`,
`last_reset_type`, `last_reset_system_ns`,
`last_reset_completed_system_ns`, and `reset_phase`. The event and first
completed post frame must have matching generation and start/completion stamps.
The analyzer does not infer execution from a GUI click, a change in Q, an
assignment generation, or clocks alone. Consumer `now_ns` and system `system_ns`
are distinct clock domains; a remote producer clock is not used for this boundary.

## A, B and C are distinct observation layers

### A: admitted input or actual legacy Tracker

MVIP identities appear in the separate generic input catalog.
`MonakaInputConfigurationApi.Candidate` and the GUI
`monaka-input/controller.ts` `InputCandidate` carry identity, capabilities,
usability and selection data, **no orientation**. The MVIP path does not create
an ordinary SolarXR `Tracker` to back a tracker pose widget. Its actual input
object is `MtpObservationBackend.Sample.pose`, returned by `samples()`.

For this path, `input_trackers` records `source_domain = MVIP_ADMITTED_INPUT`,
`orientation_source = MVIP_ACCEPTED_SAMPLE_ORIENTATION`, admitted
`rotation_xyzw`, identity/target, declared component validity, sample age and
`ingested_rotation_quality` from the stored observation. The admitted numeric Q
can remain present when invalid or stale. `rotation_selected` and
`selected_rotation_xyzw` separately report whether this identity owns rotation
in the already pinned same-tick constraint (`selection_basis = PINNED_TICK_CONSTRAINT`).
An unselected input is not thereby declared unusable. The added A observation
reads the pure `ConstraintPipeline.observations()` store snapshot and does not
call the freshness-evaluating `observations(now, assignment)` overload, which
updates freshness cursors. `freshness_evaluation = NOT_REEVALUATED_DIAGNOSTIC`
makes that limit explicit; this diagnostic does not reevaluate current usability.
The existing detailed-profile baseline observation call is outside this addition
and remains unchanged. A explicitly records
`gui_orientation_available = false` with
`gui_orientation_unavailable_reason = CATALOG_ONLY_NO_ORIENTATION_DISPLAY`.
An admitted producer Q is not assumed to be mutated by a consumer Reset.

For actual legacy Tracker objects only, `source_domain = SLIME_TRACKER_OBJECT`
records raw, reference-adjusted and identity-adjusted rotations read from that
object, with `sending_data` reporting its existing sending state rather than a
new usability evaluation. The existing GUI mappings are:

| GUI value | Existing server source |
| --- | --- |
| `TrackerData.rotation`, raw table values | `DataFeedBuilder.createTrackerRotation` → `Tracker.getRawRotation()` |
| `rotationReferenceAdjusted`, reference-adjusted table values | `DataFeedBuilder.createTrackerData` → `Tracker.getRotation()` |
| `rotationIdentityAdjusted`, IMU model preview preference | For mounting-enabled trackers, `Tracker.getIdentityAdjustedRotation()`; reset-only trackers use raw rotation. |

`hooks/datafeed-config.ts` requests all three values.
`TrackersTable.tsx` chooses raw/reference-adjusted values through `useTracker`;
`IMUVisualizerWidget.tsx` prefers identity-adjusted Q and falls back to raw.
`Tracker.getRotation()` includes existing reset/filter/smoothing behavior;
`getIdentityAdjustedRotation()` is a different visualization transform.
The diagnostic A reference-adjusted Q is not a new GUI truth source.

Private FK input proxies and computed/output trackers are **never relabeled as
GUI input Trackers**. There is no fabricated MVIP Tracker object or display.

### B: actual FK consumption

`consumed_rotation_inputs` remains the existing
`HUMAN_SKELETON_FK_ROTATION_READ` observation: target, private input identity,
tick, read site, component masks and the exact consumed Q. The existing FK read
boundary and input values are unchanged. B's private identity need not equal
A's admitted input identity.

### C: skeleton and computed output

`bones_before`, `bones_after` and `computed_trackers` retain their existing
meaning. CHEST-related global bone Q and computed Tracker Q are observations
around the same pose update. They are not physical teacher measurements or
proof of SteamVR rendering; `steamvr_observed` remains false.

These facts and deltas do not select CASE A/B/C/D. In particular, unchanged MVIP
admitted Q with changed B/C can be normal consumer reset behavior. Alignment
cannot be established from a quaternion change alone. The absence of a GUI
orientation correspondence is an explicit limit for the next HIL, so the
diagnostic cannot guarantee that every run will yield one of those cases.

## Capture and offline extraction

The existing detailed diagnostic remains the default. The explicit
`full_reset_window` profile retains a bounded sampled pre buffer, flushes it
at the first actual Full Reset, forces the first completed post frame, records
the post window, and emits `monaka.capture_end.v1`. Target pre/post windows are
five seconds each at 20 Hz. A capture must contain at least two actual seconds
on each side for complete extraction; a configured five-second window alone
does not prove that time was recorded. Subsequent resets inside the open window
are recorded but do not extend the first reset's capture deadline.

Configure the existing frame path with the JVM property
`monaka.diagnostics.frames.path` or environment variable
`MONAKA_DIAGNOSTICS_FRAMES_PATH` (an absolute, new output path). Select the
profile with `monaka.diagnostics.frames.profile` or
`MONAKA_DIAGNOSTICS_FRAMES_PROFILE=full_reset_window`; the property takes
precedence. These are startup-only diagnostic controls, not an instruction to
restart or replace a running application during this offline work.

The end record reports termination reason, written records/bytes, drops,
failure/incomplete state and capture profile/window settings. Queue, I/O or
limit failure remains diagnostic failure and does not stop reset/tracking.
`records_written` and `bytes_written` exclude the summary itself.
`actual_pre_ns` and `actual_post_ns` are observed spans, not configured targets.
Termination reasons include `POST_WINDOW_COMPLETE`, `EXPLICIT_CLOSE`,
`MAX_BYTES`, `MAX_RECORD_BYTES`, `MAX_RECORDS`, `WRITER_FAILURE` and
`RESET_FAILED`; I/O failure may prevent a summary from being written.
Start recording and wait for the pre window before requesting the single reset;
do not substitute runtime replacement or hardware operations for this offline task.

From the repository root:

```powershell
python -B tools/analyze_full_reset_trace.py <trace.ndjson> --later-frames 20
python -B -m unittest discover -s tools -p test_analyze_full_reset_trace.py -v
```

The analyzer only reads the trace and writes JSON to stdout. It preserves exact
decimal tick/time/generation values. Record indexes are zero-based nonblank
NDJSON record indexes. It extracts the last pre frame, actual reset events,
first completed post frame and the Nth captured frame after first post;
`--later-frames` counts captured frames, not server ticks or elapsed seconds.
It reports A/B/C values and matching-identity angular deltas with
`q` equivalent to `-q`. Missing, ambiguous and invalid Q comparisons stay
explicitly unavailable.
Repeated FK reads with identical Q and component masks retain their read
counts and share one delta; differing values remain ambiguous. Computed chest
outputs include the existing `UPPER_CHEST` Tracker target as well as CHEST.
The selected A Q and selection metadata remain in extracted snapshots; the A
delta compares admitted Q. B retains the exact consumed current Q independently.
The analyzer requires a matching complete-profile end record with the normal
completion reason and consistent generation, record count and observed spans.
It reports the writer's byte counter; it does not independently recalculate
that counter from reserialized JSON.

`COMPLETE` means complete trace extraction with the required evidence, not
Reset functional PASS. `PARTIAL` means the execution boundary is proven but
the time window, later frame, layer or end evidence is incomplete. `BLOCKED`
means the actual boundary cannot be proven or the input is malformed.
Exit code is 0 for COMPLETE and 2 otherwise. Legacy traces without reset
events/generation, missing/failed events, clock-only guesses and truncated JSON
cannot produce a complete result. Preserve the original trace separately.

## Existing and focused software checks

Existing reset behavior coverage includes core `SkeletonResetTests`,
`MountingResetTests`, and the existing solver/integration tests; diagnostic
coverage includes desktop `SameFrameDiagnosticsTests`. Their execution belongs
to the integration owner and is not claimed by this document.

O7's small in-memory known trace independently expects exact pre/post/later
ticks, a 90-degree B/C delta and zero A delta, with an explicit MVIP GUI limit.
Negative controls cover absent/missing/failed/wrong-owner events, mismatched
frame linkage, old traces, insufficient pre/post/later evidence, writer/drop/end
failure, missing layers and truncated JSON. It checks sign equivalence,
large integers beyond binary64 precision, CLI exit status and unchanged input.

Physical HIL: **NOT RUN / HIL_WAIT**. CUTOVER: **NOT RUN**. No statement here
claims Reset behavior was fixed.
