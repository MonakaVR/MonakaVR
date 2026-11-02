# Local Windows runtime

Run `Build_Runtime.cmd` explicitly after installing Java JDK 17+, CMake, the
Visual Studio C++ toolchain, Node/pnpm and Git. The first online build initializes
the existing official submodules at their tracked commits and installs the frozen
pnpm lockfile using `Build/Dependencies/pnpm`. It disables the root prepare hook
and rebuilds only the dependency lifecycle packages listed in
`pnpm-workspace.yaml`: `@parcel/watcher`, `@sentry/cli`, `@swc/core`, `electron`,
`electron-winstaller`, `esbuild`, `register-scheme`, and `unrs-resolver`.
The command-local Gradle cache defaults to `Build/Dependencies/gradle`.
`Build_Runtime.cmd --offline` requires pinned sources, prepared JavaScript
dependencies, the Electron runtime and tool caches. It skips submodule updates,
pnpm install and dependency rebuilds, and passes `--offline` to Gradle. Missing dependencies fail
visibly. The explicit local package is unsigned, uses the already installed
Electron distribution, and disables Sentry build uploads.

The build compiles the desktop JAR, the existing bindings provider, the shared
protocol and Electron GUI, then packages the standard desktop into the fixed
`Build/runtime/win-unpacked` directory. A later successful build updates that
same directory. No launcher edits, dated package, artifact catalog or historical
receipt are needed. The build does not start, install or register the products.

The desktop Gradle intermediate is `Build/gradle/server/desktop`; the native
provider intermediate is `Build/bindings-provider/win-x64`, matching the shared
packager and CI input. The local package configuration inherits the shared
artifact inputs without appending another `extraFiles` list.
Electron compiles into `gui/Build/out`, preserving resolution through the GUI's
local Node dependencies. Its packaged `main` is `out/main/index.js`, mapped from
that compiler stage. Ordinary distribution packaging uses `Build/Artifacts`;
the explicit local runtime build uses `Build/runtime/win-unpacked`. CI's build,
download, package and upload paths follow these locations. Android APK/AAB
outputs retain their established location; only their shared GUI asset input
follows `gui/Build/out/renderer`.

Local packaging first writes `Build/runtime/package-stage`. Only a successful,
complete package is promoted to the fixed runtime. Any previous runtime is
preserved under `Build/runtime/previous-success`; that backup is never a launch
fallback. A failed compile/package leaves the previous runtime in place.

The workspace's `launchers/Start_MonakaVR.cmd` is the single normal entry.
It opens the built desktop, which owns its normal server lifetime. The launcher
and its private helper live outside this repository and locate this checkout
relative to their own location. There is no separate normal server launcher.
The entry accepts only optional `--check`: it lists missing files and the planned
command without executing the GUI, server or Java.
`FILES_PRESENT` reports file prerequisites only, not configured input, validated
settings, running services, output readiness or physical validation.

The launcher uses the existing consumer data directory and never copies old
configuration or controls input producers. Java is found through `MONAKA_JAVA`
(executable path), `JAVA_HOME`, or PATH; the GUI receives that Java home only in
its child environment. Java 17+ is required; `--check` does not execute a version
probe. The shared external bootstrap discovers Python through `MONAKA_PYTHON`
or PATH. An explicit invalid override fails closed. The repository's
`scripts/python_entry.cmd` remains an explicit-build helper.

Build tool overrides are `MONAKA_CMAKE`, `MONAKA_PNPM` and `MONAKA_JAVA`.
CMake can also be discovered beneath an installed Visual Studio location derived
from the standard Program Files environment. No drive, user profile, or installed
tool directory is pinned in source.

Existing producer-specific GUI controls are a separately recorded architecture
liability. This launcher adds no such control, reads no producer configuration,
and does not assert completion of the producer-independence acceptance gates.
