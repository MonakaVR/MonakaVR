# Bridge Desktop software checks

These tests own only temporary config/profile directories, local renderer port
4173, a new browser profile, and a test Electron process. They never import the
production Electron main, start Core/JAR/Bridge, open tracking transports, or
produce SteamVR/VRChat output. The production renderer/main inputs do not import
the `tests/bridge-smoke` fixture. Every fixture displays SOFTWARE TEST MODE.

Use the repository's locked pnpm dependencies and build the existing SolarXR
submodule first. From `gui/`:

```powershell
$env:MONAKA_BRIDGE_TEST_VALIDATOR = '<5fbf2a9 build>/monaka_bridge_config.exe'
$env:MONAKA_BRIDGE_TEST_P2R_VALIDATOR = '<42ec6c8 build>/monaka_bridge_config.exe'
$env:MONAKA_DESKTOP_TEST_ELECTRON = '<locked Electron 40.9.1>/electron.exe'
pnpm test
pnpm lint
pnpm build
pnpm check:bridge:smoke
pnpm smoke:bridge:desktop:build
pnpm smoke:bridge
# In another terminal, while the test-only Vite server owns 127.0.0.1:4173:
pnpm test:bridge:browser
```

For independent integration checkouts, choose a free task-specific port with
`pnpm smoke:bridge --port 4183` and set
`MONAKA_DESKTOP_SMOKE_URL=http://127.0.0.1:4183` in the test terminal. The native
fixture accepts loopback HTTP only. Do not stop an existing port owner.

Missing validators/Electron are failures, never skips or substituted native
successes. `bridge-tests.tsconfig.json` compiles real service/controller/IPC
modules for Node's test runner. Native CLI tests validate actual config files;
fault injection is explicit in test constructors. `bridge-smoke.tsconfig.json`
checks browser and native fixtures separately from the existing product lint.

The browser suite uses installed Edge in an independent headless profile. The
Electron suite uses the actual product preload, private IPC handler, service,
native validator and tray MenuItem. Only the native file dialog is replaced by a
cancel response. The tray item is invoked programmatically; manual OS tray
clicks, file picker interaction and packaged-app startup are not claimed.

The earlier unadopted WPF proposal's 55 policy/health and 22 tray assertions are
preserved at `MonakaBridge` commit `6700d6304fac6ce7af5dd51ec41b32537c9ad35c`.
Behavior coverage is traced in the Desktop implementation report. The port does
not delete tests or relax Bridge gates. This app has one Bridge control panel,
so its tray navigates to that panel instead of owning a second status/writer
model. Existing legacy Bridge tray/CLI writers remain external writers; the
Desktop byte-conflict checks cannot make their final rename atomic with ours.

Screenshots, TAP, Playwright JSON and build logs are written under ignored
`build/`. Selected final receipts are archived in `docs/gui-desktop-20261015/`.

## Shared native configuration transaction

Use the newly built shared-transaction CLI for both MONAKA_BRIDGE_TEST_VALIDATOR
and MONAKA_BRIDGE_TEST_P2R_VALIDATOR. Set MONAKA_BRIDGE_TEST_LEGACY_VALIDATOR
explicitly for the old-validator negative control. The service now delegates
commit to native exact-byte CAS, not a Desktop-only lock/rename. Old supplemental
race-check.mjs describes the frozen pretransaction service at commit9da19816;
run that historical entry against its historical compiled module, not the updated
service. Current cooperating-writer/fault process evidence is in the workspace
config-write-transaction-20261015 task and native config_transaction_process test.
The selected CLI must return monaka-config-transaction-v1. Unchanged legacy writers
remain outside the cooperative guarantee; see Bridge docs/config-write-transaction.md.
