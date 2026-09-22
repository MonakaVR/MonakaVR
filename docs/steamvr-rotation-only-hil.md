# Production SteamVR ROTATION_ONLY HIL

This procedure exercises the production path:

`VIVE -> MonakaBridge -> MTP v2 -> MonakaVR production VRServer -> existing IK -> SlimeVR OpenVR Driver -> SteamVR`

It does not change Main/Fallback policy, Fusion, IK, tracking semantics, the
committed `server/desktop/monaka-mtp.json`, Bridge approvals, or SteamVR driver
registration. The helper never pairs a tracker, kills an existing process, or
starts/stops SteamVR. It never declares a hardware result.

## Prepare and start

Start the already configured VIVE backend and MonakaBridge. Keep their current
mapping/session state stable. The helper selects exactly one Bridge mapping for
`vive-local-1 / 23:34:e4:5a:fe:39` and requires the resulting HIP Main identity
to be `monaka-bridge-local-1 / vive-local-1 / altra-1`. Identity values used in
the generated config are copied from that selected mapping, not substituted for
the committed config with constants. Input-space and revision approval remains
an explicit Bridge operation; the helper does not approve either one.

First inspect a preparation without building or starting the runtime:

```powershell
.\scripts\run_rotation_only_steamvr_hil.cmd -PrepareOnly
```

Review the printed identity and the new `build\steamvr-hil-*` directory. Then
start the production runtime in the foreground:

```powershell
.\scripts\run_rotation_only_steamvr_hil.cmd
```

To use a different Bridge configuration explicitly:

```powershell
.\scripts\run_rotation_only_steamvr_hil.cmd -BridgeConfig C:\path\to\bridge.json
```

The helper requires Java 17, runs `:server:desktop:shadowJar`, verifies that the
JAR contains `dev.slimevr.desktop.Main`, then runs:

```text
java -Dmonaka.mtp.config=<temporary-config> -jar <slimevr.jar> run --monaka-mtp
```

It does not pass `--steam` or an installer option. Before building or launching,
it checks UDP 29811. If another MonakaVR, `mtpHilCapture`, or receiver owns the
port, it prints the endpoint/PID and stops; close that process manually. UDP
29813 is not needed for this production run.

Each invocation creates a new `build\steamvr-hil-<timestamp>-<guid>` directory
and refuses an existing directory. It contains the exact temporary
`monaka-mtp.json`, its SHA-256, a selected Bridge mapping/profile snapshot, and
`manifest.json` with repository HEAD, start time, identity, port, timeout,
Bridge mapping revision, VIVE input space/revision and the production JAR path
and hash when a runtime was built. Preserve this directory with the Bridge/VIVE
logs and the operator's physical-action times.

## Physical test order

1. Establish FULL tracking.
2. Move the tracker and confirm that the SteamVR virtual tracker follows in 6DoF.
3. Occlude the cameras after localization to enter ROTATION_ONLY.
4. Continue physically rotating the tracker while occluded and observe whether
   SteamVR rotation remains responsive.
5. Remove the occlusion and reacquire FULL tracking.
6. Confirm that position returns without a large rotation jump or virtual
   tracker recreation.
7. If practical, repeat the FULL -> ROTATION_ONLY -> FULL cycle once more.
8. Type `exit` in the production server console for normal shutdown.

## Manual verdict checklist

The operator/reviewer, not the helper, decides the result.

- FULL: the virtual tracker follows normally.
- ROTATION_ONLY: production runtime stays alive, the existing IK path remains,
  stale position is not retained, rotation follows physical motion, Main/body
  calibration remains, and tracker topology is not rebuilt every frame.
- FULL recovery: position returns, rotation remains continuous, virtual tracker
  identity remains stable, and calibration remains.

The previous effective-HIP and transport-silence HIL is supporting evidence, but
does not make this production VRServer/IK/SteamVR run pass automatically. Until
the observations above are manually recorded, hardware and SteamVR HIL remain
**NOT RUN**.
