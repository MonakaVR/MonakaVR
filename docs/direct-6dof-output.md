# Direct resolved 6DoF output

The [Hybrid tracking foundation](hybrid-tracking-foundation.md) supersedes the
original mutually exclusive Direct/IK design. Direct now participates in IK by
default; `useAsIkConstraint: false` is an explicit opt-out. The native driver
compatibility contract below is unchanged.

Base: `17c4c9bb712b425bd72911837c7df010dee5bd62`. This is an opt-in local
composition/output feature. MTP wire v2, its pinned codec, source identities,
coordinate convention, UDP ports and Main/Fallback policy are unchanged.

## Data flow and configuration

```
MTP UDP / existing Slime trackers
  -> existing admission, lifetime and freshness
  -> ConstraintResolver / MainFallbackPolicy
  -> ResolvedTrackingPose (selected component value + owner + age + space)
       useAsIkConstraint -> ConstraintIkWriteback -> HumanSkeleton -> computed tracker
       Direct output     -> OutputPose -> DirectConstraintOutput -> ProtobufBridge -> compatible OpenVR driver
```

Config remains version 2. Add `"outputMode": "direct"` to the desired assignment
in a **separate copy** of the known-good local config. Do not change its approved
space, publisher/source/tracker IDs or fallback relation. Absent `outputMode`
defaults to `ik`; explicit `ik` is also accepted. Unknown modes fail validation.
Save/load preserves the mode and existing Main/fallback references. Example
assignment shape (replace identities with the actual configured identities):

```json
{
  "body": "HIP",
  "outputMode": "direct",
  "mainTracker": {
    "kind": "mtp",
    "publisher_id": "<configured publisher>",
    "source_id": "<configured source>",
    "tracker_id": "<configured tracker>"
  },
  "rotationFallbackTracker": { "kind": "slime", "name": "<configured fallback>" }
}
```

Omit `rotationFallbackTracker` if none is intended. Direct bodies must have a
SteamVR role. Assignments sharing a Direct SteamVR role (for example HIP and
WAIST) are rejected, including a competing IK assignment. Changing the set of
Direct output bodies/modes requires a server restart; runtime attempts fail
closed. Changing samples/modality never changes output registration or identity.
Private solver input proxies can still rebuild on component capability changes;
that existing solver limitation is documented in the Hybrid foundation.

One internal computed output object is allocated per Direct body at startup,
with serial `monaka-direct:resolved-v1:<BODY>`. Its numeric ID and body role stay
unchanged across FULL/ROTATION_ONLY/NONE/FULL. It is not registered as a raw input.
Existing `FeedbackExclusion` additionally rejects its serial/internal-computed
identity. SteamVR output substitutes this object for the computed tracker with
the same role. Automatic skeleton-based sharing cannot remove it on loss.
Other roles retain existing sharing settings. Direct selection explicitly
enables output for that role; GUI mode editing is outside this change.

Direct consumes the resolver's selected components through `ResolvedTrackingPose`
and `OutputPose`, not raw packets or a second fallback selector. Main FULL owns
both components. Otherwise the
existing explicit usable rotation fallback wins, then usable Main rotation;
neither yields an unavailable pose. No fallback/IK/held position is serialized.
The existing MTP 500ms age policy alone decides sample freshness. Pausing clears
Direct output components. Shutdown/error invalidates its output view.

## Read-only compatibility audit

`Tracker.hasPosition/hasRotation` describe constructor capabilities, not live
sample validity. The legacy `ProtobufBridge` writes XYZ whenever `hasPosition`
is true and does not set `data_source`. This path, including computed trackers
with `sampleModality == null`, remains unchanged. Direct serialization instead
uses nullable resolved components, with no velocity/prediction.

Generated Position already provides optional x/y/z and optional `data_source`;
quaternion scalars have no independent presence bits. No generated protobuf or
schema changed. WindowsNamedPipeBridge serializes the same ProtobufMessage with
its existing length prefix; it does not interpret modality and is unchanged.

Audited official SlimeVR driver source:
[TrackerDevice.cpp at dcc0f56b](https://github.com/SlimeVR/SlimeVR-OpenVR-Driver/blob/dcc0f56bcb2a3196d6f92b1ed1d029faa425b931/src/TrackerDevice.cpp).
Its PositionMessage ignores data_source, retains the previous position if X is
absent, and unconditionally sets poseIsValid=true / Running_OK. IMU and NONE
therefore cannot safely be sent to that unmodified decoder.

The locally present `slimevr-openvr-driver-win64/slimevr/bin/win64/driver_slimevr.dll`
was read-only hashed: SHA256
`7946ee117f363cb15f4f31c633ecb8826f91e394b2c83298d0498c6cf18b8eed`.
Its exact source provenance and the binary loaded by SteamVR were **not**
established. A hash alone is not a compatibility claim. No local installed
driver was replaced, loaded or restarted.

## Minimal driver overlay and compatibility gate

`scripts/prepare_direct_driver.py` requires the exact clean upstream commit and
its exact clean submodules, and creates a new isolated source directory. It
never edits the supplied checkout or installs a driver. The overlay is owned
here under `native/direct-driver`; no other Monaka repository is modified.
The copied source retains upstream MIT/Apache and dependency license files.

Server requests `monaka-direct-output-v1?` using the existing UserAction message
and a fresh per-connection token. The overlay echoes that token with
`monaka-direct-output-v1`. No protocol version/schema change is involved.
Until a matching reply arrives, Direct TrackerAdded/Position are withheld.
Disconnect clears compatibility, reconnect discards queued Direct poses, and
old-token replies cannot reopen the gate. Repeated valid replies do not recreate
trackers. Legacy trackers remain usable with an old driver. Absence of capability
is logged; there is no silent fallback to IK for a Direct assignment.

Only serials starting exactly with `monaka-direct:resolved-v1:` get the new
decoder behavior. Legacy serials follow upstream behavior, including their
handling of missing data_source. Direct initial poses are invalid, and a Status
OK message cannot manufacture a valid Direct pose.

| Resolved components | Protobuf | Direct OpenVR DriverPose_t |
|---|---|---|
| position + rotation | XYZ present, quaternion, FULL | connected, poseIsValid=true, Running_OK |
| rotation only | XYZ absent, quaternion, IMU | connected, poseIsValid=false, Fallback_RotationOnly, live qRotation |
| neither | XYZ absent, NONE | connected, poseIsValid=false, Running_OutOfRange |

OpenVR has one whole-pose validity flag, not optional XYZ. Invalid positional
storage is cleared, never advertised as a valid origin or stale position.
Rotation-only explicitly carries qRotation and Fallback_RotationOnly while
invalidating the full 6DoF pose. Existing upstream reverse intake also recognizes
Fallback_RotationOnly independently of bPoseIsValid. This does **not** prove
every SteamVR application renders/uses such a tracker. SteamVR compositor,
applications and physical origin-jump behavior remain a hardware HIL gate.

Malformed Direct messages fail closed: missing/unsupported data_source,
incomplete/nonfinite FULL XYZ, invalid quaternion, or IMU with XYZ. The overlay
does not add a second freshness clock, position hold, calibration or vendor
logic. Existing universe handling is retained.

## Reproduce software validation (no installation)

Run from the feature worktree. These commands create isolated artifacts; do not
point them at the live HIL executable or an existing prepared source directory.

```powershell
Set-Location 'C:\Users\nynyp\Downloads\6Dof\MonakaVR\build\direct-6dof-worktree'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --console=plain
if ($LASTEXITCODE -ne 0) { throw 'JVM validation failed' }

# For a fresh reproduction, clone into a NEW path and check out this exact commit.
git clone https://github.com/SlimeVR/SlimeVR-OpenVR-Driver.git build/direct-upstream
git -C build/direct-upstream switch --detach dcc0f56bcb2a3196d6f92b1ed1d029faa425b931
git -C build/direct-upstream submodule update --init --recursive
python scripts/prepare_direct_driver.py --source build/direct-upstream --output build/direct-prepared
if ($LASTEXITCODE -ne 0) { throw 'Driver preparation failed' }
$cmake = 'C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ctest = Join-Path (Split-Path $cmake) 'ctest.exe'
& $cmake -S build/direct-prepared -B build/direct-native -G 'Visual Studio 18 2026' -A x64 -DSLIMEVR_BUILD_TESTS=ON
if ($LASTEXITCODE -ne 0) { throw 'Driver configure failed' }
& $cmake --build build/direct-native --config Release --parallel 6
if ($LASTEXITCODE -ne 0) { throw 'Driver build failed' }
& $ctest --test-dir build/direct-native -C Release -R '^monaka_direct_pose$' --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Driver tests failed' }
& .\build\direct-native\Release\monaka_direct_pose_test.exe .\server\desktop\build\direct-protobuf-captures
if ($LASTEXITCODE -ne 0) { throw 'JVM/native protobuf interop failed' }
```

The upstream mock-bridge test uses the production `SlimeVRDriver` pipe name.
Do **not** run it alongside live HIL. It and the real-runtime integration
executable are NOT RUN here. Upstream non-transport unit tests can be run with
`build/direct-native/Release/tests.exe '~[Bridge]'`.

Tests cover resolver owner selection with/without Slime fallback, all modalities,
freshness, stable identity, explicit IK opt-out/default compatibility, config roundtrip,
feedback rejection, UDP -> production hook -> Direct protobuf, old-driver and
reconnect gating, real generated JVM/native protobuf decoding, and malformed
driver input. Historical Direct baseline results at
`fcdf98bca8e8d1ecb68150f0a417b2564ea6d567` (current results are in the Hybrid document):

- Core: 592 tests, 0 failures/errors/skips (3 new Direct regression tests).
- Desktop: 12 tests, 0 failures/errors/skips (3 new Direct regression tests).
- JDK17 shadowJar and existing separate-process UDP `mtpProcessE2E`: PASS.
- Native Release driver build: PASS; Direct CTest: 1/1 PASS.
- Actual JVM protobuf captures -> native generated decoder -> Direct pose conversion: PASS.
- Upstream non-transport units: 6 cases / 31 assertions PASS.
- Optimized Python overlay preparation reproduced the tested source: PASS.

The native tests invoke the conversion used by TrackerDevice, not a live
OpenVR host/TrackedDevicePoseUpdated call. These results are software evidence,
not SteamVR HIL or a release cutover approval.

## Next human HIL (NOT RUN in implementation)

1. Save current HIL logs and configuration. Stop the existing test server/SteamVR
   yourself when ready. Back up the current driver. Install the isolated built
   compatible driver through the existing manual SlimeVR driver procedure; do
   not load both old and new copies. No automatic installation is supplied.
2. Copy the known-good Monaka config to a new path and change only the chosen
   assignment's outputMode to direct. Keep the proven space/identity/fallback.
   Start the new JAR with options **before** `run`:

   ```powershell
   $config = Read-Host 'Absolute path to the separate Direct config'
   & "$env:JAVA_HOME\bin\java.exe" "-Dmonaka.mtp.config=$config" -jar .\server\desktop\build\libs\slimevr.jar --monaka-mtp run
   ```

   Run from an isolated HIL working directory with your intended Slime config;
   use an absolute JAR path if the working directory differs. Do not run two
   Monaka intakes on the same UDP port. Verify the capability response and one
   Direct serial/WAIST registration rather than a competing computed WAIST.
3. FULL: verify absolute position/rotation track the resolved MTP values without
   IK displacement. Obscure optical tracking: verify IMU/rotation-only state,
   absent position, and live fallback rotation (then repeat without fallback).
   Do not accept a valid origin pose or held old position as success.
4. Reacquire FULL: position returns, same serial/ID/role, no recreation. Stop
   publisher packets: after resolver timeout position disappears; rotation may
   remain only if the configured fallback is still usable. With no usable
   fallback the Direct pose must be unavailable.
5. Record driver/server versions, config, MTP and resolver evidence, SteamVR
   state and application behavior. Repeat an unchanged IK/default config as a
   regression comparison. Physical origin-jump resolution is **unverified**
   until these observations pass.

Hardware, VIVE/PICO, SteamVR interactive and application consumption: **NOT RUN**.
