# Supplemental software checks

These are opt-in diagnostics, separate from the standard GUI tests. No backend,
Core, tracking transport, global service event, or real output is started.

`race-check.mjs` uses the actual frozen native CLI and the actual legacy tray
`SaveRoute` method compiled from unchanged frozen source. The tray constructor,
`SetRoute`, global event and shared status memory are deliberately not invoked.
The critical compare/rename schedule uses the existing Desktop service test seam
to run the real external writer immediately before the real rename. Passing the
reproduction means the known lost-update risk was observed, not eliminated.

`isolated-production-main.mjs` redirects application/session data and denies
child processes, Node sockets/pipes/fetch and non-local-file Chromium requests
before importing the unchanged built production main. The production renderer's
default WebSocket request is recorded and cancelled before contacting a service.
`startup-check.mjs` checks the real production protocol, preload, renderer and
tray menu, and the production shutdown path. It is a guarded native startup
check, not an unrestricted normal launch or manual OS interaction.

`package-check.mjs` builds an unsigned GUI-only test package using the locked,
cached Electron runtime. It omits Core/JAR/driver extras, changes test package
metadata/entry and preserves all built product output bytes. The real package
executable then runs the guard with `app.isPackaged=true`. This does not validate
the full signed runtime distribution, installer, real outputs or hardware.

From the repository root, compile the read-only legacy source with:

```powershell
$output = Join-Path (Get-Location) 'build/gui-supplemental-20261015'
./gui/tests/bridge-supplemental/compile-legacy.ps1 -FrozenBridgeSource '<frozen Bridge>' -OutputDirectory $output
```

From `gui/`, after the existing product build and bridge-tests TypeScript build:

```powershell
$env:MONAKA_SUPPLEMENT_ROOT = '<this repo>/build/gui-supplemental-20261015'
$env:MONAKA_LEGACY_CONFIG = '<5fbf2a9>/monaka_bridge_config.exe'
$env:MONAKA_LEGACY_TRAY_HARNESS = "$env:MONAKA_SUPPLEMENT_ROOT/legacy-tray-save.exe"
$env:MONAKA_STARTUP_EXE = '<locked Electron 40.9.1>/electron.exe'
$env:MONAKA_TEST_ELECTRON_DIST = '<locked Electron 40.9.1 runtime folder>'
node tests/bridge-supplemental/race-check.mjs
node tests/bridge-supplemental/startup-check.mjs
node tests/bridge-supplemental/package-check.mjs
# To reverify an already built test ASAR without rebuilding: --verify-only
$env:MONAKA_STARTUP_MODE = 'packaged-gui-test'
$env:MONAKA_STARTUP_EXE = "$env:MONAKA_SUPPLEMENT_ROOT/package/win-unpacked/MonakaGuiIsolatedTest.exe"
node tests/bridge-supplemental/startup-check.mjs
```

The generated `.exe`/ASAR are test assets under ignored build output; never
publish or install them as the product. Reports must distinguish browser E2E,
guarded native main startup, guarded test-package startup, unrestricted product
startup and manual OS/HIL tests.
