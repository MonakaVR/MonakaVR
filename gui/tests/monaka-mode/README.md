# Tracking output mode tests

Run from the repository root with JDK17, Node and fixed pnpm dependencies:

```powershell
.\gradlew.bat :server:core:test :server:desktop:test --console=plain
pnpm --dir gui exec tsc --project tests/monaka-mode/tsconfig.json
Push-Location gui
node --test build/monaka-mode-tests/tests/monaka-mode/*.test.js
pnpm exec playwright test --config tests/monaka-mode/playwright.config.ts
Pop-Location
pnpm --dir gui lint
pnpm --dir gui build
```

Core tests generate `server/core/build/mode-test-classpath.txt` for the Java
subprocess integration. This is required: that test fails instead of silently
substituting a mock if the classpath is missing. Run Core tests first.

- `controller.test.ts`: mock state-machine/validation tests, including uncertain
  save results, duplicate clicks, disconnects, stale responses and reload.
- `core-process.test.ts`: the actual Core JSON handler and atomic file writer in
  a child JVM with a fresh temporary configuration. No network/device/runtime
  process is contacted; stdio is the test transport.
- `component.spec.ts`: real React component with mock API in headless Edge,
  including empty-Fluent English fallback, review/cancel, per-body drafts,
  unknown mode, disconnect and pending restart. The test starts/stops its own
  Vite server at `127.0.0.1:4187`; it refuses to reuse another server.

The fixture is not a product page. Normal Tracker GUI integration remains
reserved for the separate post-Issue-#2 PR. No backend, SteamVR or HMD hardware
validation is implied by these software tests.
