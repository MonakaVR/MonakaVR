# HIP Hybrid continuity vertical slice

This work starts from `39612f33dca59d7fe0dbf9a194d32a49645232d1` on
`feature/direct-6dof-output`. The separate live HIL checkout, its local
configuration and running processes are untouched. The software path uses the
existing MTP v2 intake, resolver, IK, output tracker, Protobuf bridge and
compatible Direct driver. No wire or vendor backend changes are involved.

## Data flow and transitions

The Main FULL observation enters the unchanged `MainFallbackPolicy` and
`ConstraintResolver`. Its selected position and rotation feed both visible
Direct output and `ConstraintIkWriteback`. `HumanPoseManager.update` continues
on every tick; the post-IK hook reads the current computed HIP from the same
HumanSkeleton. That computed value is a potential fallback even while Direct
Main is visible. The output tracker remains one object with one ID, serial and
body role. Mode transitions replace its typed `OutputPose` only.

For a Hybrid HIP assignment:

| State | Visible output |
|---|---|
| `MAIN_DIRECT` | Exact current Main position and rotation. Background IK still receives the Main constraint. |
| `FALLBACK_ACTIVE` | When Main position disappears, interpolate from the last emitted pose toward current aligned IK position and resolver-selected rotation. Then follow those current values. If either is unavailable, emit only genuinely available components. |
| FULL dwell | A single FULL packet cannot enter reacquisition: both the elapsed dwell and a newer Main sample are required. Gradually approach current aligned background position while retaining the last emitted rotation; the first FULL frame retains the previous output. |
| `REACQUIRING` | Start at the last emitted output; linearly interpolate position and use the existing shortest-path quaternion interpolation toward the current Main pose. The latest Main target can move during convergence. |
| `MAIN_DIRECT` after convergence | At the configured duration, use exact current Main components again. |
| `UNAVAILABLE` | Pause or no selected rotation. No valid position is manufactured from zero or old numeric tracker storage. |

Loss during convergence starts a new fallback transition at the **last emitted**
pose. Subsequent FULL must again satisfy the dwell. If current background pose
or confirmed alignment disappears, the controller removes position validity;
it does not keep a stale IK pose. The resolver alone chooses raw rotation owner.
While a transition is interpolating, the component owner denotes the selected
destination; its numeric value is the controller's intermediate output.

The existing `ConstraintIkWriteback` remains the only body correction path.
Capability-mask changes can still require a private proxy/topology refresh so
the solver does not read stale position. The computed tracker, skeleton, IK
solver and calibration remain the same; repeated frames of one modality do not
rebuild topology. There is no new solver or drift estimator.

## Provisional configuration

Hybrid remains opt-in using `outputMode: hybrid`, `useAsIkConstraint: true` and
`continuity: background_ik` on the HIP assignment. A verified operator
assertion that skeleton world and the configured Monaka world are identical is
required for background fallback: `backgroundIkAlignment.kind` must be
`confirmed_same_space`, with the exact configured `id`, convention and
revision. Without that assertion, fallback is unavailable. This is an
assertion, not a calibration algorithm or hardware proof.

The optional root-level `continuityTuning` object is local config version 2:

```json
{
  "continuityTuning": {
    "stableFullDwellMs": 150,
    "reacquireDurationMs": 300,
    "fallbackBlendMs": 150
  }
}
```

Defaults are the values shown, **provisional and not HIL tuned**. Each field
accepts an integer from 1 to 10000 ms; invalid or misspelled fields fail
validation. Existing configs without Hybrid fields retain legacy IK output.
Direct output with `continuity: none` retains its original immediate component
behavior. These timings never enter the wire protocol or source resolver.

The alignment seam converts the computed skeleton-world pose into the exact
Monaka coordinate space before interpolation. The existing driver then handles
its normal SteamVR universe transform. The code does not infer shared space
from similar numbers or insert VIVE/PICO-specific transforms.

The production hook logs discrete state, Main modality/validity, selected
owners, output source, background availability and transition reason. Numeric
pose movement and elapsed progress do not create per-frame logs.

## Software checks and HIL boundary

The deterministic Core tests exercise first FULL, FULL to ROTATION_ONLY/NONE,
current-tick IK fallback, invalid/unverified background, one-packet FULL,
dwell, position and shortest-path rotation convergence, re-loss during
convergence, completion and clock ordering. The real HumanPoseManager test
verifies Main-driven computed HIP changes during Direct output, a live IMU
input, calibration retention, 500 ms freshness and feedback exclusion. The
Desktop production-hook test exercises the same stable output object through
FULL / ROTATION_ONLY / FULL / convergence using the actual tick integration.
Existing Main/Fallback, legacy Slime, Direct and protocol tests remain active.

Build command from this isolated worktree with JDK 17:

```powershell
Set-Location 'C:\Users\nynyp\Downloads\6Dof\MonakaVR\build\direct-6dof-worktree'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Hybrid vertical slice software validation failed' }
```

Software tests establish the HIP path and discrete numeric continuity under
their fixtures. They cannot establish physical quality or confirm that the
operator's skeleton-world and Monaka-world spaces coincide. A separate HIL
config and compatible Direct driver are still needed. Human HIL should record
FULL, optical occlusion/ROTATION_ONLY, continuing IMU IK, FULL reacquisition
and a second occlusion while watching one serial/ID/role and the state-change logs. Start
the separate config with JVM options before `run`:

```powershell
& "$env:JAVA_HOME\bin\java.exe" "-Dmonaka.mtp.config=<absolute separate config path>" -jar .\server\desktop\build\libs\slimevr.jar --monaka-mtp run
```

Hardware, VIVE/PICO devices, SteamVR interactive behavior, physical alignment,
continuity feel and final timing/curve tuning: **NOT RUN**. No live driver
replacement, pairing, process termination or release cutover is performed.
