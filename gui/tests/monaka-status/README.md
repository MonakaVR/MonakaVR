# Read-only Monaka input status component

Status: **COMPONENT READY / PRODUCT SCREEN INTEGRATION PENDING**.

Base: `origin/dev` at `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`, fetched
2026-10-21 JST. This matches the coordinated work document's baseline.
Branch: `feature/monaka-source-status-panel`.

## Public interface

`src/monaka-status/view-model.ts` exports the pure
`buildTrackingStatusModel(snapshot, context)` projection and its display types.
`src/components/monaka-status/MonakaTrackingStatusPanel.tsx` exports a memoized
React component. Use the existing GUI `LocalizationProvider`:

```tsx
<MonakaTrackingStatusPanel
  snapshot={snapshot}
  connection={connection}
  minimumGeneration={knownAssignmentGeneration}
/>
```

`snapshot` is the existing `InputSnapshot | null`. `connection` is required and is
one of `connected`, `offline`, `disabled`, `loopback-denied`, `error`, `unknown`.
The host owns the existing snapshot cadence and must supply request/transport
failures promptly; a cached snapshot alone never proves a current connection.
Pass immutable new snapshots. Mark `connected` only after a fresh snapshot in the
current connection. Neither the component nor the projection owns transport.

`minimumGeneration` is optional, uses the existing decimal-string assignment
generation, and is compared using `BigInt`. It is not a sample timestamp or a
freshness counter. The component also remembers the last accepted generation and
selected IDs; same-generation validity updates are allowed, changed selected IDs
and older generations are rejected. Selected Q/6DoF IDs remain authoritative.
Changing the candidate set never substitutes another ID based on display names.
Unknown additive fields are ignored for compatibility with future mode APIs;
malformed known fields and contradictory catalogs fail closed. Unknown enum values
are displayed as unknown.

On failure the component retains validated assignments, marks current usability
unknown and synthesizes `Saved (offline)` rows for selected IDs. An initially
offline mount may use a valid cached snapshot for assignment retention only.
These placeholder rows do not decode IDs or invent publisher/source/tracker
metadata. Manual selections and explicit unassigned values remain as supplied.
For direct projection users, feed the previous result's `acceptedSnapshot` or
`retainedSnapshot` back as `previousSnapshot` to preserve the same behavior.

The API has no Core-instance epoch. After the host confirms a new Core lifetime,
remount with a new React `key` and clear its `minimumGeneration`. Ordinary request
failures/reconnects must retain the same component instance. The panel cannot
detect silent snapshot staleness while the host reports `connected`; the host
must report timeout/missing-response health. There is no invented receive time.

The `mode` column reports only the existing API field and labels activation as
unknown. Source usability does not establish Direct output, aligned world space,
solver writeback, physical IMU provenance or SteamVR visibility. Slime remains an
orientation-only Q source. Capability is independent of dynamic usability.

The component uses the shared Fluent keys `monaka-tracking-capability`,
`monaka-tracking-validity`, `monaka-tracking-offline`,
`monaka-tracking-assigned`, `monaka-tracking-unknown` and the three
`monaka-tracking-mode-*` keys. Missing keys use English fallback without repeated
missing-key logs. Locale resources remain owned by task D. Other explanatory
copy is English; full product localization remains a separate integration task.

## Dedicated checks

From the repository root, install the locked workspace dependencies and initialize
the pinned `solarxr-protocol` submodule using the repository's normal setup, then:

```powershell
pnpm --dir solarxr-protocol build
pnpm --dir gui exec tsc --noEmit --project tests/monaka-status/tsconfig.json
node gui/tests/monaka-status/run.mjs
pnpm --dir gui exec playwright test --config tests/monaka-status/playwright.config.ts
pnpm --dir gui exec prettier --check src/monaka-status src/components/monaka-status tests/monaka-status
pnpm --dir gui lint
pnpm --dir gui build
```

These tests are intentionally separate from the existing `pnpm test` script; no
shared package/configuration files were edited. The Node runner bundles the real
production sources using the esbuild already pinned through Vite. Type-check the
dedicated project as above, since bundling itself does not type-check. Component
tests render actual React markup and verify translation fallback and escaping.

The Playwright configuration starts its own fixture server on `127.0.0.1:4197`
and refuses to reuse an existing listener. It launches installed Edge headlessly
in an isolated profile and terminates the server/browser when complete. It does
not start or connect to Core, Bridge, Electron, SteamVR or tracking transports.
The fixture selector belongs to the test harness, outside the read-only panel.
Only Vite HMR opens a WebSocket. Network assertions permit only fixture GETs/HMR;
the panel has no editable controls, RPC/save import, clock, timers or effects.

Fixtures cover Q-only, Pos+Q, unknown, conflict, identical tracker names with
different IDs, saved offline, invalid reconnect, Slime Q plus separate MVIP 6DoF,
zero candidates, disconnected, disabled and denied loopback access. Transition
checks cover retained manual selections, invalid responses, stale generations,
changed IDs, recovery and an initially disabled cached snapshot. Idle commit
counts and unchanged DOM on parent rerenders guard against background work;
these are not performance benchmarks or hardware latency measurements.

Node TAP, Playwright JSON, traces on failure, product build/lint logs and the four
fixture screenshots are generated under ignored `gui/build/`. Tests run only
against owned software fixtures. Final run: 39 Node tests and 17 browser tests
passed; dedicated type/format checks and product lint/build passed. Initial
sandbox attempts could not access localhost; the successful browser run used
the permitted host environment. It does not establish product screen behavior.

## Contract proposals for later work

No extra RPC or fields are implemented here. A future host/API contract would
need explicit Core-instance identity and response health/receipt metadata to
disambiguate restart generation resets and missing snapshots. Showing independent
physical IMU authentication, Main sample time, modality history, continuity,
actual IK writeback, SteamVR final output, saved/active mode or applied restart
would each require separately documented authoritative fields and ownership.
The current panel deliberately displays none of those as computed facts.

## Boundaries and remaining gates

Only the two dedicated production directories and this fixture/test directory
are changed. No Core, input selection controller/socket, writer, config,
translation resources, Bridge/tray, App, tracker cards or existing settings page
is modified. No source selection or auto-reselection behavior is introduced.

Issue #2's normal Tracker GUI integration and a later small integration PR remain
pending. Manual product UI operation, Electron smoke and hardware/HMD/PICO/VIVE/
SteamVR/HIL are **NOT RUN**. No production correction/admission setting is enabled.
The implementation is software-only and is proposed as a Draft PR to `dev`;
it must not be merged by this task.
