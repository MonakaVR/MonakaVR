# Task 5 operation and migration

Task 5 accepts only the actual Task 4 handoff from
`acc329dce90dd6ba21387029cd54b6fc2d82fe8c`. Run
`python scripts/verify_task5_upstream.py` to verify the supplied ZIP, external
manifest, report, content hashes, derived provenance and embedded fixed Task 1
kit. No sibling checkout is required to build this repository.

## Enabling input

MTP is off by default. Enable with `java -jar slimevr.jar --monaka-mtp run`,
`-Dmonaka.mtp.enabled=true`, or `MONAKA_MTP_ENABLED=true` (CLI, property,
environment priority). False starts no MTP socket, worker, configuration reader,
pipeline or skeleton hook. Existing Slime settings and output serials remain.

Create `monaka-mtp.json` beside the selected `vrconfig.yml`, or select it with
`-Dmonaka.mtp.config=C:/path/monaka-mtp.json`. The following values are an example;
use the **actual** shared world ID/revision and logical IDs configured in Bridge.
Missing world configuration fails input startup and is logged; it is not guessed.

```json
{
  "version": 1,
  "port": 29811,
  "timeout_ns": "500000000",
  "space": {
    "id": "your-shared-world",
    "convention": "rh_y_up_neg_z_forward",
    "revision": 0
  },
  "assignments": [
    {"source_id": "your-bridge-source", "tracker_id": "your-logical-tracker", "body": "HIP"}
  ]
}
```

Assignment keys are case-sensitive `(source_id, tracker_id)` pairs. The body is
an existing `TrackerPosition` enum name. Empty assignments are allowed: validated
unassigned inputs remain in `runtime.mtp.samples()` diagnostics and never enter
IK. Counters, including `UnassignedPose`, are available in
`runtime.inbox.diagnostics()`. Edit the configuration and restart to apply file
changes. The generic assignment API supports server/UI edits with immutable
generation snapshots; call `MonakaConfiguration.save` to persist them atomically.
There is no automatic live file watcher or new GUI in this task.

Legacy `--pico`, `monaka.pico.*`, and `MONAKA_PICO_*` only produce a migration
warning. They cannot enable MTP or load the old receiver. An old serial-to-body
table can be imported with `TrackerBodyAssignments.migrateLegacy` **only** with
an explicit serial-to-logical-identity Bridge map, then saved in the new settings.
Missing/ambiguous/colliding correspondence is rejected atomically. Device slots,
vendor packet IDs and serial substrings are never inferred.

## Runtime and component behavior

One `MonakaRuntime` owns the profile registry, assignment registry, runner and
pipeline. Both Slime and MTP are registered there. The tick stage runs after
bridge reads and raw tracker ticks, before `HumanPoseManager.update`; the prior
`onTick` ordering is unchanged. UDP decode/validation runs only on its worker.

MTP uses the supplied JVM codec, not a second JSON implementation. Binary64 to
Float conversion rejects overflow. C1 coordinates are used directly, with xyzw
converted to `Quaternion(w,x,y,z)`. No vendor mapping or calibration is repeated.
The configured space ID, convention and revision must all match.

Each pose's local sample time is fixed once as `receivedAt - (sent_at - timestamp)`.
Before-local-epoch samples are unusable. Sequence watermarks reject duplicates
and reverse order; state has a separate sequence stream and cannot refresh pose
age. Session changes retire the old session and invalidate only that source.
Absent, mapping/space invalidation and backend failure clear affected constraints.
Pose expiry uses the existing policy: age greater than the configured timeout.

The existing resolver chooses **each component separately**, by quality rank,
then profile priority, sample time and source ID. `tracked` maps to TRACKED;
`degraded` maps to DEGRADED; invalid/zero-confidence components are null/LOST.
MTP priority is 100, Slime is 0, so a TRACKED Slime rotation outranks a DEGRADED
MTP rotation. Confidence and input provenance remain diagnostic metadata; this
is not probability-based fusion. Slime retains its existing status/timeout path.

Private inputs replace only body targets explicitly managed by MTP assignments.
Their immutable position/rotation flags reflect the resolved component mask.
Position-only body inputs reach existing IK without contributing a fake identity
rotation to FK. The head retains a separate positional anchor behavior. Other
Slime/HMD inputs remain ordinary inputs. Pose frames update values; assignment,
mask and lifetime/mapping invalidation rebuild bindings. Pause/resume clears MTP
samples while retaining replay watermarks and resets private constraint history.
No solver iteration or convergence algorithm was changed.

Queue capacity is 1024 with a 256-envelope tick budget. Source/device state is
bounded to 64 sources and 1024 logical trackers. At 256 retired sessions per
source, that source fails closed until runtime restart instead of accepting old
replays. Dropped queue entries do not refresh existing samples. There is no
cross-process clock-epoch or precise network clock-sync assumption.

## Feedback and outputs

Existing computed tracker output uses the distinct `human://` serial namespace;
`ProtobufBridge` sends these exact serials. It differs from Bridge Direct's
`monaka-direct:` namespace. `monaka-solver:` is also reserved/excluded. Those
namespaces and `monaka-private:` are excluded from Slime raw input, MTP source /
tracker / input-device identities and the optional skeleton input view. Internal
computed output is excluded without excluding real computed HMD/SteamVR input.
Private inputs are never registered in `VRServer.allTrackers`. The `both` routing
regression verifies one physical MTP input and zero mirror feedback observations.

## Removal gate and retained reference files

At source commit `26f971a075601156ba3d13c2f8d6dd87769d0215`, before disabling any
legacy source, 579 core tests (including old PICO unit tests) and 5 generic desktop
tests passed. The exact suite manifest and XML ZIP are in
`evidence/pre-removal-tests.json` and `evidence/pre-removal-tests.zip`.
The original deliberate diagnostic failures and four missing-native-property
desktop failures remain archived in `evidence/baseline-failures.zip` as FAIL.

After these gates, the `dev/monaka/tracking/pico/**` main/test sources are retained
as reference files but excluded from both Kotlin and Java source roots. Main uses
the generic integration. The old native probe task now explains the migration
and fails explicitly instead of loading a receiver. JNA remains for unrelated
Windows, serial and desktop functions. Existing licenses/notices are preserved.

Useful old test semantics moved as follows:

| Previous responsibility | Generic replacement |
| --- | --- |
| Stable serial/body table and updates | Explicit logical assignment migration, collision/unknown checks, persisted round trip |
| Position units, quaternion basis, missing components | Fixed C1 fixture/xyzw adapter, Float guard and per-component validity tests |
| Receiver sample time, optical loss, freshness | First-admission age, duplicate/state/timeout and independent component fallback tests |
| Enumeration/session/removal/provider failure | Incremental omission, absent, retired sessions, space/mapping invalidation, backend isolation |
| Native/JNA publisher through runtime/tick | Real loopback worker and separate-JVM UDP through common runtime and existing IK numerical change |
| Disabled native integration | No socket/config/hook and identical Slime computed output with gate false |

## Task 2 Phase B / hardware

Use the final Task 5 report, built JAR inspection and actual handoff hashes from
`dist/` as evidence. Source-only changes or these instructions alone are not
completion evidence. The validation script checks that no archived PICO class
or native carrier symbol is present in active MonakaVR classes/JAR.

PICO hardware, five-tracker operation, VIVE dongle/Hub-stopped behavior, physical
axes/scale/quaternion/map semantics, coexistence, HMD, SteamVR driver registration,
restart/routing, same-point Direct-vs-MTP and hardware cutover are **NOT RUN**.
Task 2 must retain its own legacy consumers until its separate compatibility and
hardware cutover gates pass. No Task 2/3/4 repository is changed by Task 5.

C1 remains wire 1.0 candidate / master reconciliation pending.
