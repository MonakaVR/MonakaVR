# Independent input settings route smoke

Run from `gui/` after the frozen workspace dependency install:

```text
pnpm exec tsc --noEmit --project tests/monaka-input-route/tsconfig.json
pnpm exec playwright test --config tests/monaka-input-route/playwright.config.ts
```

The suite loads the real application entry point, providers, layout, routing,
settings navigation, and unchanged input widget/controller/socket. Edge must be
installed. The Vite server is owned by Playwright on loopback. Set
`MONAKA_INPUT_SMOKE_PORT` to an available OS-assigned test port (default 4207).
The offline runner reserves and releases an ephemeral port immediately before
launch; strictPort prevents falling through to another port on a race.
Vite cache stays in this checkout's build directory, including when dependencies
are shared read-only. The resolved font package is explicitly allowed for serving.

`CoreUiFixture` replaces only the public consumer GUI WebSocket endpoint. It
accepts the existing `monaka_inputs_v1` JSON RPC; normal SolarXR binary messages
are left unanswered. It does not implement MVIP, receive a wire packet, run a
producer, or prove interoperability with independent producer implementations.
Core transactions and real ingress are covered separately by the existing JVM
and MTP process suites.

The Electron case uses an isolated main/preload, real Electron renderer and the
real App. Fixture storage stays under `gui/build/monaka-input-route`. Every
Bridge API call crosses an instrumented IPC handler which fails and increments
a counter; this route must make zero calls. The fixture does not launch the
product Electron main or its retained legacy services. Whole-product startup
independence and real OS interaction require separate validation.

This suite adds no producer lifecycle, configuration or private API dependency.
It does not fulfill the two independent conforming MVIP implementation gate.
Screenshots and traces are written beneath `gui/build/monaka-input-route`.
The repository's existing CI workflow is unchanged; run this dedicated suite
locally as shown above.
