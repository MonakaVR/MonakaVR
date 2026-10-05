# Phase 2B-5B: driver-local HMD frame observation probe

Phase 2B-5A ended with **Outcome C**: the pinned driver has no stable identity proving live frame continuity. Provider-specific raw poses and the existing wire convention (right-handed, +Y up, -Z forward, meters, Hamilton quaternion) are source-proven; CoordinateSpace id/revision and complete frame lineage remain unknown. The 5A audit was not committed, so its premises are summarized here. **RawHmdPoseInput remains runtime-blocked. Hardware/HIL: NOT RUN.**

`MonakaHmdFrameProbe` collects changes that this driver observes. Its `detectedBoundaryGeneration` records only observed changes and is **not a trusted frame epoch**. Lack of an observed boundary is **not proof of frame continuity**. No observer status authorizes a CoordinateSpace, calibration epoch, RawHmdPoseInput, predictor or Position Correction runtime.

## Pinned source and available signals

Source locations below refer to upstream driver `dcc0f56bcb2a3196d6f92b1ed1d029faa425b931` and OpenVR `91825305130f446f82054c1ec3d416321ace0072`. `scripts/prepare_direct_driver.py` is the source of truth. The source checkout and both submodules remain read-only. Only new disposable output directories are generated and built.

| Signal | Availability decision | Source and limits |
| --- | --- | --- |
| Universe ID | PROVEN AVAILABLE | `src/VRDriver.cpp:119-136`, existing `Prop_CurrentUniverseId_Uint64` query. Property error means unavailable, never a guessed zero. |
| Universe-ID transition | PROVEN AVAILABLE | Compare successfully sampled IDs; sampling can miss changes, including ABA between polls or during disconnection. |
| Applied cache translation/yaw | PROVEN AVAILABLE | `src/VRDriver.cpp:238-267`, `src/IVRDriver.hpp:12-19`; binary32 tx/ty/tz/yaw copied from the exact local `trans` used by the send conversion. |
| Cache refresh success | PROVEN AVAILABLE | `src/VRDriver.cpp:124-129`; existing SearchUniverses return value and cache replacement. Lookup success does not prove live standing-frame accuracy. |
| Cache refresh failure | PROVEN AVAILABLE | `src/VRDriver.cpp:124-130,598-632`; absent search result leaves the previous cache untouched. |
| Relevant OpenVR events | AVAILABLE BUT SEMANTICS PARTIAL | `openvr_driver.h:973-981,3884-3886`, existing RunFrame pump; supported consumption/constants proven, exhaustive delivery unproven. |
| Seated reset | AVAILABLE BUT SEMANTICS PARTIAL | `VREvent_SeatedZeroPoseReset`; receiving it proves a reset signal, not this send path's exact resulting frame. |
| Standing/chaperone change | AVAILABLE BUT SEMANTICS PARTIAL | Standing reset and chaperone universe-change constants; auxiliary notifications have weaker semantics below. |
| Tracking loss/recovery | PROVEN AVAILABLE | Copied raw-pose validity, connection and tracking result; availability only. |
| Raw pose validity | PROVEN AVAILABLE | Existing GetRawTrackedDevicePoses call and returned pose fields; prediction argument `0.0f` is not acquisition time. |
| Driver/observer lifetime | PROVEN AVAILABLE | Opt-in Init creates one observer; opaque random owner plus in-process instance counter distinguishes instances for diagnostics only. |
| Transport reconnect | PROVEN AVAILABLE; excluded as frame signal | OnBridgeConnect and bridge handling are unchanged; pose/cache sampling pauses during bridge disconnection. Reconnect itself advances neither counter. |

`Prop_PreviousUniverseId_Uint64` exists (`openvr_driver.h:486`) but is not queried or used as lineage. Complete frame coverage, live standing origin, provider-specific internal changes and physical acquisition time remain **UNKNOWN**.

IVRSystem live raw-to-standing and IVRChaperone queries are **UNAVAILABLE FROM PROVEN DRIVER CONTEXT**. Existence in `openvr.h` does not prove supported server-driver use. Pinned server-context helpers (`openvr_driver.h:4360-4588`) provide host/properties/logging interfaces, not a proven client query path. No client runtime API or new runtime interface is requested.

## Pure observer contract

`native/direct-driver/MonakaHmdFrameProbe.hpp` is a C++20 core without OpenVR objects, logging, clocks, network or locks. Inputs are copied optional observed ID, optional applied-cache ID, optional `AppliedUniverseTransform`, lookup state, tracking validity/connection and primitive result. HMD position/quaternion/velocity, transport session and server Tracker identities are absent.

`HmdFrameObservation` is an immutable value snapshot: observerOwner, observationSequence, detectedBoundaryGeneration, copied values, lastSignal, proofState, universeChanged/transformChanged/boundarySignalObserved flags and `coverageIncomplete=true`. Held snapshots do not follow later driver changes. Owner is an observer-instance namespace only; new instances may start counters at zero. Owner stability and owner/generation pairs are not promoted to trusted frame lineage.

Every copied pose observation or mapped event advances observationSequence once. Boundary generation advances once per input if a successfully observed ID changes, applied-transform bits change, or an explicit boundary signal arrives. Simultaneous reasons remain visible independently. Initial inputs establish a baseline without advancing generation. Last known IDs/applied values are retained internally for comparison across unavailable gaps; current unavailable fields still appear unavailable. This detects differing endpoints, not unseen intermediate transitions.

- A→B→A advances generation twice; final A does not restore earlier lineage.
- Same ID T1→T2 advances generation in synthetic tests. Production does not reload an existing same-ID cache; this phase preserves that limitation.
- Same numeric transform plus seated/standing reset or universe-change event advances generation.
- Pose motion, tracking loss/recovery and transport reconnect alone do not advance generation.
- Event inputs retain **last sampled pose context**, not a new event-time transform/property query.

Transform comparison is exact binary32 bit equality for tx/ty/tz/yaw. Signed zeros differ; no epsilon, hash identity or NaN normalization is used. Nonfinite values remain observable with `INVALID_TRANSFORM`, including repeated inputs, without identity substitution. A changed invalid value may still be an observed content change. An absent cache is unavailable, not an invented identity snapshot.

Lookup states distinguish unknown, cached_not_refreshed, refresh_succeeded, refresh_failed and universe_unavailable. Failed B lookup can retain A's applied cache: observed universe B, applied-cache universe A, applied transform A, lookup failed. Old cache availability never promotes lookup failure to stable. Sampling already cached A reports cached_not_refreshed, not fictional refresh success.

Proof/status is limited to UNKNOWN, OBSERVED_STABLE_INPUTS, BOUNDARY_SIGNAL_OBSERVED, UNIVERSE_CHANGED, TRANSFORM_CHANGED, TRANSFORM_LOOKUP_FAILED and INVALID_TRANSFORM. Invalid content takes priority, then lookup failure, then unavailable/mismatched inputs or unavailable tracking, then boundary reasons. Separate flags retain reasons under failure statuses. Stable inputs mean repeated available copied content only; coverage remains incomplete even after recovery. There is no verified-continuity state.

## Exact event coverage and integration

`MonakaHmdFrameProbeDriver.hpp` maps pinned constants, compiled against the real copied OpenVR header by the software test.

| Exact pinned constant | Value | Signal | Boundary from event alone |
| --- | --- | --- | --- |
| `VREvent_ChaperoneUniverseHasChanged` | 801 | ChaperoneUniverseChanged | Yes |
| `VREvent_SeatedZeroPoseReset` | 804 | SeatedZeroPoseReset | Yes |
| `VREvent_StandingZeroPoseReset` | 808 | StandingZeroPoseReset | Yes |
| `VREvent_ChaperoneDataHasChanged` | 800 | ChaperoneDataChanged | No; header says this will never occur with the new chaperone system |
| `VREvent_ChaperoneTempDataHasChanged` | 802 | ChaperoneTempDataChanged | No; same deprecated-system warning |
| `VREvent_ChaperoneSettingsHaveChanged` | 803 | ChaperoneSettingsChanged | No; settings notification does not prove origin change |
| `VREvent_ChaperoneFlushCache` | 805 | ChaperoneFlushCache | No; client-cache reload notification, no refresh performed here |
| `VREvent_ChaperoneRoomSetupStarting` | 806 | RoomSetupStarting | No; starting notification |
| `VREvent_ChaperoneRoomSetupCommitted` | 807 | RoomSetupCommitted | No; working-copy commit, effects on origin/send cache unproven |

Haptic vibration, device activation, property/battery changes and all unmapped events are ignored by the observer. Original event-vector content/order and TrackerDevice haptics remain unchanged. There is one original PollNextEvent call site. Pinned `Driver_API_Documentation.md:1541-1558` describes supported server-driver consumption and warns that events can expire after a frame; runtime completeness remains unproven.

The opt-in adapter is created before bridge/pose worker startup and never reassigned while workers run. RunFrame taps **after** events.push_back and **before** the original initialization guard. A dedicated mutex serializes event observations, copied pose observations and diagnostic logs; it never acquires the device mutex or adds an event queue/pump. The pose thread retains cache ownership; existing broader cache-read concurrency is not repaired here.

Universe query and existing search result are instrumented without changing refresh cadence, same-ID no-reload, search-failure fallback or formula. The exact local `trans` used in the conversion is copied into the input. Immediately before the original HMD Position SendBridgeMessage, that same raw sample/transform is observed and its snapshot can be logged. Later event mutations cannot change the held snapshot. If no HMD Position is sent, one availability observation records that iteration's sampled cache without a pose-association line. No additional pose/property query, chaperone file read or network message is introduced. Disconnected bridge intervals remain blind to pose/cache sampling; delivered RunFrame events can still be observed.

Driver observer owner, transport session and server Tracker object/sourceEpoch remain **three distinct identities**. The observer knows neither transport epochs nor Tracker objects. Nothing crosses the protobuf boundary.

## Diagnostic use for a future HIL gate

Default **OFF**. Only exact string `1` enables either environment flag, read once in Init. Off means no observer/owner allocation, observer lock/formatting, added per-frame log, extra file I/O or network traffic. Original transform/send paths remain in place. Enabled instrumentation may affect scheduling; no hardware/performance equivalence is claimed for that mode. Owner-generation initialization failure disables the requested optional probe with a diagnostic.

For a **future separately authorized HIL run**, start the process launching SteamVR from an environment containing the following, after separately deploying the reviewed overlay driver. This phase does not install, restart or launch SteamVR:

```powershell
$env:MONAKA_HMD_FRAME_PROBE = '1'
# Optional sampled pose associations, at most one per second:
$env:MONAKA_HMD_FRAME_PROBE_POSES = '1'
# Launch SteamVR from this environment during the future HIL run.
```

Without the main flag the pose flag has no effect. Mapped events and state/boundary/availability changes use existing VRLogger/IVRDriverLog. Association lines are separately opt-in and limited to at most one per second; every pose input still advances sequence.

Pinned `Driver_API_Documentation.md:1718-1748` locates logs at `<steam_install_dir>\logs\vrserver.txt`, commonly `C:\Program Files (x86)\Steam\logs\vrserver.txt`, and previous session at vrserver.previous.txt. Search for stable prefix `MONAKA_FRAME_PROBE_V1` after SteamVR's date/driver prefix.

One-line key=value fields: kind, owner, seq, boundary, signal, proof, universe or unavailable, applied_cache_universe or unavailable, applied_f32_bits or unavailable, lookup, tracking_valid, connected, tracking_result, all three reason flags, coverage_incomplete=1, value_context and poseProbeObservationSequence or unavailable. The transform is four 8-digit IEEE binary32 hex words in tx/ty/tz/yaw order, preserving signed zero/nonfinite payload. Event context says last_pose_sample; pose context says same_raw_pose_sample. Only kind=pose_association identifies a Position about to be passed to SendBridgeMessage; it does not prove downstream delivery. SteamVR timestamps are log time, never physical acquisition time. There is no freshness/temporal pairing claim.

## Software verification and reproduction

Tests cover initial/repeated inputs, A→B and ABA, same-ID transform changes, same-transform explicit resets, motion through the production tracking-copy adapter, loss/recovery, failed lookup with old cache, missing-property gaps, NaN and ±infinity in all four components, signed zero, subnormal changes without epsilon, simultaneous reasons, immutable snapshots, new owner namespaces, all mappings/unrelated-event exclusions, structural transport exclusion, rate-limited association logging and concurrent adapter entry points. The standalone probe executable loads no OpenVR runtime; the existing Direct and upstream driver tests remain registered.

```text
python scripts/prepare_direct_driver.py --source <clean-pinned-driver> --output build/frame-probe-overlay-<new-name>
cmake -S build/frame-probe-overlay-<new-name> -B build/frame-probe-native-<new-name> -DSLIMEVR_BUILD_TESTS=OFF
cmake --build build/frame-probe-native-<new-name> --config Release --parallel 8
ctest --test-dir build/frame-probe-native-<new-name> -C Release --output-on-failure
python scripts/test_frame_probe_overlay.py --source <clean-pinned-driver> --overlay build/frame-probe-overlay-<new-name>
gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --no-daemon --console=plain
```

Preparation requires exact pins, clean source/submodules, a nonexistent output outside the source tree and exactly one occurrence of each patch anchor. New native headers/test are copied from repository sources; generated overlays are not committed. CMake adds the standalone C++20 probe executable/ctest and preserves the existing Direct target.

Windows verification uses JDK 17 / Gradle 8.14.4 and Visual Studio 18 2026 x64 / MSVC 14.51.36231. Cached dependency **sources**, not previous objects, are provided via FETCHCONTENT_SOURCE_DIR_{PROTOBUF,SIMDJSON,LIBUV,UVW,CATCH2,ABSL}, with FETCHCONTENT_FULLY_DISCONNECTED=ON. Cached CPM 0.42.3 is staged into the fresh build and checked by upstream's configured hash. Configure/build produce new objects in the fresh build. Initial sandboxed wrapper/configure attempts hit cache-write/MSVC detection restrictions and were rerun with existing tools outside those restrictions. Actual gate results are recorded after execution; Hardware/HIL stays NOT RUN.

On this Windows toolchain, configure explicitly with `-G "Visual Studio 18 2026" -A x64` and `"-DCMAKE_CXX_FLAGS=/DWIN32 /D_WINDOWS /GR /EHsc"`, matching the previous successful native build. Without /EHsc, simdjson disables exception-based conversion APIs used by unchanged upstream code; the first build failed for that reason and was reconfigured/rebuilt. No upstream workaround was applied. The overlay preservation test also checks unchanged conversion blocks, search methods, same-ID guard, query counts, event handoff, reconnect method and wire/bridge sources. Existing-output preparation rejection is separately verified.

The final required native gate uses upstream's default `SLIMEVR_BUILD_TESTS=OFF`: both Monaka standalone executables remain built/registered independently of that optional suite. An initial ON configuration also built upstream tests, but their mock server reused production pipe `\\.\pipe\SlimeVRDriver`. The sandboxed run failed with a pipe permission/timeout; an unsandboxed retry encountered an already running real driver (real HMD/controller messages in the mock-server log) and failed its invalid-message assertion. Those results are retained as FAIL and discarded as contaminated software verification, not accepted HIL evidence. No installed driver was copied, edited, restarted or replaced. The mock process exited; no further production-pipe test or live-runtime interaction is performed. Any incidental visible-runtime effects of that collision are unverified. Optional upstream mock-suite validation needs a separately isolated endpoint before future execution; changing that suite is outside this probe phase. The required Direct/probe ctest gate is not dependent on that suite.

Final required verification on 2026-10-05 (Asia/Tokyo):

| Gate | Actual result |
| --- | --- |
| Core | PASS: 681 tests; failures/errors/skips 0 |
| Desktop | PASS: 52 tests; failures/errors/skips 0 |
| shadowJar | PASS |
| mtpProcessE2E | PASS: all 11 scenarios, separate synthetic processes |
| Fresh overlay preparation | PASS: build/frame-probe-overlay-2b5b-20261005 |
| Native configure / Release build | PASS: build/frame-probe-native-build-2b5b-20261005, explicit /EHsc, final optional upstream suite OFF |
| Existing monaka_direct_pose_test | PASS |
| New monaka_hmd_frame_probe_test | PASS |
| Final required ctest | PASS: 2/2, 0 failed; optional upstream mock-suite failures remain separately recorded above |
| Pinned path preservation / existing-output rejection | PASS |
| Upstream / OpenVR / linalg | Exact pins; tracked and untracked status clean after build/tests |
| Protected worktree patch | Unchanged, still untracked; SHA256 9c04281ea21499a7f5ff89797e7e9620f3d268db5b15fe29e6b67b6a9f33d26d |
| Hardware/HIL validation | NOT RUN; accidental mock-suite runtime contamination is not accepted evidence |

Logs are kept in ignored build artifacts: frame-probe-server-gates.log, frame-probe-native-configure*.log, frame-probe-native-build*.log, frame-probe-ctest*.log, and frame-probe-verification-2b5b/mtp-process-e2e/result.json. No earlier failed optional attempt is relabeled PASS. Existing upstream C4715 warnings (protobuf Objective-C helper and GetRoleName) remain outside this change.

## Remaining gate and classification

**Recommended next step: HIL coverage gate before trusting a protocol design.** Exercise seated/standing resets, room setup commit, universe/provider frame changes, same-ID/same-transform resets, failure, loss/recovery, reconnect and restart. Determine which events reach this pump and their ordering relative to pose samples/sends. Verify the installed artifact separately, audit cache correctness separately and measure enabled-probe scheduling/log rate. Phase 2B-5C may then design explicitly qualified observation lineage; transmitting this counter alone would not establish a trusted frame epoch.

| Classification | Established fact / remaining limit |
| --- | --- |
| PROVEN | Probe API carries observations with incomplete coverage and does not authorize RawHmdPoseInput or emit server frame metadata. |
| UNIT-VERIFIED | Observed-boundary semantics, fail-closed status, comparisons, mapping, software adapter serialization/logging. |
| SOURCE-VERIFIED | Host/properties/log APIs, event pump, pinned constants, raw pose source, applied transform/cache behavior and preserved formula/wire. |
| HIL-UNVERIFIED | Runtime event coverage, ordering, resets, live/cache equality, provider behavior, installed artifact and enabled performance. |
| UNKNOWN | Complete live frame identity/revision, acquisition time, unseen changes and continuity through gaps. |

Frame epoch: **NOT YET TRUSTED**. CoordinateSpace runtime: **BLOCKED** (convention proven, id/revision unknown). RawHmdPoseInput: **BLOCKED**. No CoordinateSpace or frame/calibration epoch is emitted to the server. No protobuf frame metadata, MainDecoupledHipPredictor or Position Correction runtime path is implemented. MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication and intended visible SteamVR behavior remain unchanged; physical equivalence was not exercised.
