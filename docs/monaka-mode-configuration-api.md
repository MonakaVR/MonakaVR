# Offline tracking output mode configuration

Status: **API+COMPONENT READY / NOT WIRED TO NORMAL TRACKER GUI**.
The reusable component is not mounted in App, assignment screens or Bridge UI.
Normal Tracker GUI integration is reserved for a later PR after Issue #2.

## Transport and compatibility

The existing bounded, loopback-only, owner-thread `monaka_inputs_v1` WebSocket
namespace is unchanged. `snapshot` and source-selection `save` retain their
request shapes. All previous response fields remain present. In particular,
assignment `mode` still means the **active runtime** output mode.
No tracking wire, generated protocol, port or schema version is changed.

New snapshot/ successful save response members:

| Member | Meaning |
| --- | --- |
| `readbackToken: string` | Opaque instance/review epoch plus SHA-256 of exact configuration bytes. Echo unchanged on save. |
| `restartRequired: boolean` | Global input-edit fence. A persisted mode change has not been applied to this runtime. |
| `backgroundIkAlignmentConfirmed: boolean` | Existing explicit shared-world assertion matches configured space/revision; does not certify physical alignment or current IK availability. |
| assignment `activeMode: string` | Same value as legacy `mode`. |
| assignment `savedMode: string` | Mode read from the offline configuration, including a committed pending change. |
| assignment `restartRequired: boolean` | Saved assignment differs from the active assignment. The global fence also blocks edits to other bodies. |
| assignment `modeOptions: array` | `{mode, available, reason}` for ik/direct/hybrid, using existing complete configuration validators. |

Generation remains a decimal **string**, including values above JavaScript's
safe integer range. Offline mode saves do not increment the live assignment
generation. File token and generation are checked together. The token rotates
after a commit or error, and a new server/API instance invalidates old tokens.
Tokens are concurrency/review guards, not authentication credentials.

## New request

```json
{
  "type": "monaka_inputs_v1",
  "id": "review-12",
  "action": "save_mode",
  "body": "HIP",
  "generation": "7",
  "mode": "hybrid",
  "readbackToken": "<exact token from the reviewed snapshot>"
}
```

All shown members are required. Mode strings are exactly `ik`, `direct`,
`hybrid`. The body must already have an explicit Main assignment.
The GUI sends no source, mapping, calibration or participation fields.

Only the selected assignment's `outputMode`, `continuity` and (if necessary)
`useAsIkConstraint` are patched in the original JSON tree. IK and Direct use
`none` continuity and preserve existing participation. Hybrid uses
`background_ik` and requires participation (`true`). There is no opt-out GUI.
Existing role/duplicate-reference/correction binding validators still fail
closed. Unknown root, assignment and reference members, sourceSelection,
manualSix, Main/Fallback, body lengths and all calibration/space data survive.

Success returns the usual snapshot plus the added fields. After an actual
change, `savedMode` differs from `activeMode`, `mode` is still active, and
global `restartRequired` is true. A no-op produces no backup or restart fence.
No assignment publication, solver/output mutation or automatic restart occurs.

The whole input configuration is frozen until restart: both `save` and further
`save_mode` edits fail, including source drafts created before the mode save.
Another snapshot, reconnect or API wrapper around the old runtime cannot mark
the new mode active. Startup from the saved configuration makes saved/active
equal. A missing saved assignment is an incompatible readback, not an implicit
default or evidence that a mode is applied.

Errors keep `ok: false` and `error` and add `reloadRequired: true` plus the
known `restartRequired` state. A timeout/disconnect can follow a successful
commit: do not retry blindly. Explicitly reload the snapshot and inspect
saved/active and the restart fence. A file changed outside this instance
requires offline review/restart; snapshot does not silently adopt its bytes.

## File transaction and existing writers

Source save and mode save share the same transaction: bounded preimage,
nonblocking `.input-write.lock`, same-directory unique staging file,
validation of the exact staged JSON, file force, exact preimage backup named
`.pre-input-edit-<uuid>.bak`, another preimage check, and atomic replacement.
Failure before replacement leaves the configuration and live assignments
untouched. Cleanup cannot convert a completed commit into a cancellation.

The existing `MonakaConfiguration.save` now uses the same lock and a preimage
check. Loaded/written instances remember the source bytes, so an older writer
cannot serialize the old runtime over a later mode transaction. An unproven
instance, including a Kotlin data-class `copy()` that does not carry the private
file provenance, may create a new file or initialize an existing **zero-byte** regular file (only while the byte preimage remains empty), but **cannot replace an existing populated file**.
An operator must load the intended existing path first rather than silently
treat a copied object as a fresh file-owner snapshot. Simultaneous
cooperating writers are rejected. External tools that ignore file locks are
detected at the preimage checks; as with the existing transaction, no promise
is made of a filesystem compare-and-swap against an uncooperative writer in
the final check-to-rename interval. Do not edit live configuration externally.

Mode editing preserves v2/v3/v4 exactly; it does not migrate schema.
Schema v1 still requires the existing explicit identity migration. Existing
old-reader rejection of v4 source-selection intent is unchanged. No identities
are inferred or generated. Keep the backup for offline recovery after stopping
the server.

## Output semantics and GUI state

IK uses the existing SlimeVR solver. Direct exposes usable Main Pos+Q and
does not manufacture IK position on loss. Hybrid positional fallback requires
current-tick background IK in explicitly approved identical world space;
the existing sample dwell/interpolation handles reacquisition. Saving Hybrid
without alignment remains allowed when existing validators allow it, while
the UI explicitly reports positional fallback unavailable. A true alignment
assertion still does not guarantee a valid pose this tick.

`TrackingModeController` uses loaded/editing/review/saving/pendingRestart/error/
disconnected states. Review and a separate confirmation precede one save;
in-flight requests block additional saves and reloads. Cancel discards only
the current body's unsaved draft. Body switching retains other drafts.
Explicit reload discards drafts after a successful readback. Timeout,
disconnect, invalid response and conflict block edits until successful reload;
stale responses cannot replace a newer connection's state. Unknown modes and
older Core responses cannot enable editing.

Frozen Fluent keys are used for modes, active, unknown and restart-required.
English fallbacks work with an empty bundle; owner D owns translation resources.
Remaining explanatory copy is local component copy pending localization work.
No Japanese/English translation files are edited by this PR.

## Software validation

See `gui/tests/monaka-mode/README.md` for executable commands. Core tests use
temporary synthetic files only. Node controller tests are mocks. The separate
Java subprocess test uses the real Core JSON handler and real file transaction
over stdio; it does not exercise WebSocket networking. Existing dispatcher
tests cover local-only bounds, queueing and cancellation before dispatch.
Browser tests exercise the real component with a mock API and an empty Fluent
bundle. Existing Core/Desktop suites retain Direct/Hybrid/HIP correction and
source-selection regressions. Hardware, driver compatibility, SteamVR, HMD
and Electron interactive behavior are independent gates and are **NOT RUN**.
