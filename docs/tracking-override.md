# Controller Tracking Override

This feature supplies a pose to SteamVR's `TrackingOverrides` for each selected hand. The original controller remains the input device. Physical verification of that controller's input during optical loss is a separate acceptance test.

## Implementation basis and boundaries

The offline implementation starts at MonakaVR `7525d2aba86f082cb64c19bfea6797b1f65046a7` (`diagnostic/qjump-capture-20261107`, clean tracked parent tree), on the isolated `feature/tracking-override-gui` worktree. The existing runtime manifest records that source commit and candidate `04981a1714274443ac3afef99535e533`. The read-only environment inventory found SteamVR build `26496310`, bin version `1794004177`, and registered driver manifest `slimevr`. The installed driver file SHA-256 was `7946ee117f363cb15f4f31c633ecb8826f91e394b2c83298d0498c6cf18b8eed`. Which binary SteamVR actually loaded and its source provenance remain HIL checks. The inspected settings had no `TrackingOverrides` entry, had `activateMultipleDrivers=true`, and had no `driver_slimevr` section.

| Work group | Implementation surfaces | Acceptance |
|---|---|---|
| Pose and driver | MTP admitted sample and catalog, core hand model and resolver, desktop integration and Protobuf bridge, pinned `prepare_direct_driver.py` and `native/direct-driver` overlay | Explicit per-hand source and stable serial; FULL P+Q, sample age, session and frame checks; nonzero grip composition; invalid on loss/timeout; unsupported driver never receives the devices; no body role, input profile or input component |
| GUI and settings | Existing Assign Trackers route `/onboarding/trackers-assign` and catalog RPC, Monaka configuration, Electron main/preload and scoped settings transaction | Save/readback for both hands, distinct source and mapping status, authenticated edits, exact managed keys and conflicts, interruption recovery, removal from the PC with source or Core unavailable |
| Integration | Isolated worktree and fixed build, suite results, HIL runbook | Same revision across software results, pinned dependencies and binary hashes, no use of the operational profile or SteamVR pipe during software testing |

The **Assign Trackers** page displays Override beside each hand on the avatar. The hand's ordinary saved 6DoF assignment supplies the source. Its Grip offset and frame confirmation apply to that source and the configured world. A changed assignment requires a new confirmation before a valid pose can be published. The ordinary tracker sharing switches on the general settings page still control FBT roles. The left and right generic pose devices use the stable serials `monaka-override-v1-left` and `monaka-override-v1-right`. They have no body assignment or input binding.

## Pose and coordinate frames

```text
Producer or Bridge (where applicable)  T_admitted = W_source · T_native · M_tracker
MVIP admission and catalog            T_admitted plus identity, space, modality and age
Override resolver                     T_driver   = T_admitted · G_tracker_to_grip
SlimeVR driver overlay                DriverPose_t in the explicitly aligned driver frame
SteamVR                               driver universe and static TrackingOverrides mapping
```

The producer owns its published space and any upstream normalization. MonakaVR applies the hand's grip transform once, relative to the origin and axes of the published pose after any upstream mount transform. For position this is `p + rotate(q, gripPosition)`, and for rotation it is `q * gripRotation`. Identity is an explicit uncalibrated choice. Body IK mount transforms do not participate. The override requires the source space and the configured space to agree, and requires an explicit assertion that the source world is already aligned with the raw SlimeVR driver frame. The override device uses identity for its driver-side world-from-driver transform under that assertion. The ordinary SlimeVR tracker path retains its existing chaperone transform. Alignment and scale need physical measurement before using the feature in a mixed-universe setup.

Only an admitted, fresh, complete MVIP pose is valid. Legacy catalog entries without declared MVIP space and sample-age evidence are ineligible for this path. Missing or stale position, rotation-only, loss, and mismatched space invalidate the full pose. The desktop bridge converts the admitted sample's remaining lifetime to a short, conservative deadline on Windows' shared performance counter. A token-scoped lease precedes each FULL pose on the existing bridge; the driver consumes it once and checks expiry and transport loss on pose queries and updates. This deadline protects queued messages. `poseTimeOffset` remains zero because the bridge has no verified mapping from source acquisition time to the OpenVR driver clock. No sensor rate, pose prediction or velocity is synthesized. Measure motion latency during HIL independently of the freshness deadline. Device identity persists while samples go invalid and recover.

## Desktop operation

1. In **Assign Trackers**, choose the desired hand on the avatar and save its 6DoF source in the normal assignment chooser. Both hands start off.
2. Open **Offset Settings** beside that hand and save the Grip offset and frame confirmation. Turn on the hand's **Override** checkbox. Check its source status and wait for the compatible SteamVR driver to register the pose device. The driver verifies the activated device properties through the existing bridge. The desktop derives the candidate path from that serial and the `slimevr` manifest. SteamVR's actual override state is a separate observation.
3. Stop SteamVR from the PC. Keep MonakaVR open so the desktop can read the device registration confirmation. Apply the hand mapping from its details. A successful save requires a SteamVR restart. SteamVR must be started manually.
4. To release a mapped hand, stop SteamVR from the PC and turn off that hand's **Override** checkbox. MonakaVR removes its mapping before disabling the hand. The hand details also offer removal of an owned mapping when the assignment or Core is unavailable. Start SteamVR again to verify the release.

The file update targets `TrackingOverrides` and the two reserved hand mappings. The operation keeps unrelated settings and third-party mappings, checks conflicts, and retains a backup next to `steamvr.vrsettings`. It checks for stopped SteamVR, serializes MonakaVR writes to the same file across desktop instances, and checks the original settings immediately before the atomic replacement. The ownership record can reconcile an interrupted change when later edits affect unrelated keys. Valve's `IVRSettings` can write settings, but its API does not establish that SteamVR has applied the static override or provide the ownership and recovery transaction required here. The desktop shows saved state separately from active runtime state. It does not change `activateMultipleDrivers` or driver enable flags.

## Physical handoff

All physical checks are **NOT RUN** in the offline phase. Perform this plan only with separate permission to change the target environment and operate the hardware. Record the candidate source, app/server/driver hashes, config and settings SHA-256, SteamVR version, HMD route, controller and source models, and actual device paths from the SteamVR System Report. Use a copied MonakaVR profile and retain the original driver, settings backup, and PC recovery path. Start with both original controllers connected, normally tracked, and responsive. Use a known 6DoF source or an explicitly isolated conforming fixture.

Use the fixed candidate GUI in `Build/runtime/win-unpacked/SlimeVR.exe`. Launch it with the existing `--profile <absolute-copy>`, `--server-port <free-port>` and `--monaka-mtp` options. The copy holds `vrconfig.yml` and `monaka-mtp.json`; select the sources and save overrides in the GUI. During an authorized HIL window, use the candidate overlay driver package and allow only one registered `slimevr` driver. The operational launcher continues to point at its own fixed runtime.

| ID | PC operation and independent observations | Decision |
|---|---|---|
| H00 | Record both original poses, buttons, sticks, trigger, grip, and normal FBT with the feature off. | Baseline available. |
| H01 | Save and apply left only, restart as required, then translate and rotate the left source. Record both published poses and independent input events. | Left follows the source; right pose and both input paths remain unchanged. |
| H02 | Repeat with both hands and move them independently. | Hand mapping and serials remain stable, without swapped poses or changed bindings. |
| H03 | Fix the source and controller in a jig with a known relative transform; move and rotate about each axis. | Record position and quaternion residuals against a tolerance set from the jig and measurement accuracy before the run. |
| H04 | Occlude or stop the override source, then recover it. | Driver invalidity, recovery time and jump, and original input continuity are separately recorded. No old valid position during loss. |
| H05 | Occlude the original controller's optical tracking while keeping it powered and communicating. | Independently prove optical loss, then measure button, trigger, stick and grip events. Insufficient loss evidence is INCONCLUSIVE. |
| H06 | Cycle original-controller power, vary connection order, and restart SteamVR. | Record each result and manual recovery if required. |
| H07 | Remove both mappings in the PC UI, disable both hands and restart as required. | Original poses/input and FBT are restored. Other settings and override entries are retained. |

Capture the original controller pose baseline before applying an override. Use the source-side trace and a SteamVR consumer to compare published pose, and a separate SteamVR input monitor for controls. An overridden controller's reported pose is not an independent reference for the original pose. H00, H01, H02 and H07 establish the basic HIL feature. Claims about input during optical loss require H05 on the target controller. H03, H04 and H06 keep their own result. SteamVR uses a static mapping. Treat handoff after source loss and controller reconnection as observations to record. Follow the same stopped-SteamVR removal procedure if a controller becomes unusable. Preserve intervening unrelated settings and restore the pre-test driver and profile using the saved baselines when authorized.

## References

- [Valve OpenVR TrackingOverrides](https://github.com/ValveSoftware/openvr/wiki/TrackingOverrides)
- [Valve OpenVR Driver API and IVRSettings](https://github.com/ValveSoftware/openvr/blob/master/docs/Driver_API_Documentation.md)
- [Virtual Motion Tracker operational notes](https://github.com/gpsnmeajp/VirtualMotionTracker/blob/master/docs/note.md)
- [Microsoft QueryPerformanceCounter timebase](https://learn.microsoft.com/en-us/windows/win32/sysinfo/acquiring-high-resolution-time-stamps#guidance-for-acquiring-time-stamps)
- [MonakaVR Direct output and driver contract](direct-6dof-output.md)
