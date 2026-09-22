# VIVE same-device ROTATION_ONLY downstream audit

Date: 2026-09-22. Branch: `refactor/monaka-layer-separation`.

| Repository | Starting local and fetched origin HEAD |
| --- | --- |
| VIVE (read-only) | `e8ecd513391ab4f15af2941380631e7f05049bea` |
| Bridge | `1468432893505940d3953199e27e8829e45e9cda` |
| MonakaVR | `c021eb49230730dad7ac2650e7caca766f3176d4` |

VIVE's parent is `ffcb8946f2f68c90a03ea25e0492e915f2c17790`. The tracked HIL
summary establishes the previously reported hardware -> Observation 29810 gate.
No new hardware measurement or raw vendor interpretation was performed here.
The later Common Observation HIL capture did not co-record the raw status byte.
Its summarized deltas are not a newly reproduced result of this software audit.

## Findings

The downstream production path is consistent with C2. MtpInbox uses the fixed
v2 codec. MtpObservationBackend admits identity/session/sequence/mapping lifetime
state, fixes sample time once, and invokes MtpPoseAdapter. ConstraintPipeline
applies freshness; ConstraintResolver delegates to MainFallbackPolicy.

- Usable Main FULL supplies both components.
- With no explicit fallback, Main ROTATION_ONLY supplies only its own rotation.
  It is not registered as an external fallback. With an explicit usable
  fallback, that source supplies rotation instead.
- NONE supplies neither component. Numeric invalid pose fields do not revive it.
- Accepted rotation-only packets renew freshness. Duplicate/reordered packets,
  metadata and ticks do not. Transport silence expires rotation independently
  of optical loss. The existing MonakaVR boundary is age > 500ms; Bridge uses
  age >= 500ms. No timing semantics were changed for this audit.
- Modality changes alone do not increase MTP history generation. Component-mask
  changes necessarily refresh the private IK view, preserving stable Main/body
  calibration. Subsequent rotation-only frames do not rebuild per frame.
- ConstraintIkWriteback continues to use HumanSkeleton and its existing IK.
  No alternate solver, vendor mapper or calibration was added to MonakaVR.

Both repos verified the actual pinned v2 archive
`a55567425d071e7338b89f4e6caa925676014fd356aa585730deb1f9d11cd7c3`, manifest
`64488a4194166554b7f07cab083272d9f7a684cffa3256d7650e841f81556ee8`, source
`572e58cfa20b8b4335207ea5dbcb3f04c587ddff`. No protocol artifact was regenerated.

**Local config gate:** the inspected Bridge config (mapping revision 61) maps
`vive-local-1 / 23:34:e4:5a:fe:39` to `altra-1`. Both committed MonakaVR HIL
configs still assign HIP Main `altra-0`. Matching publisher/source alone is not
enough. Without aligning the intended logical tracker, this becomes
`UnassignedPose` and cannot drive HIP. Original configs were not changed.
The RF address is a capture-time routing key, not a claim of persistent hardware
identity. A reconnect may require independent mapping/space-revision approval.

## Regression coverage

`MtpRotationOnlyDownstreamTests` enters the real codec/inbox/backend/adapter/
pipeline/resolver and IK writeback with explicit fallback null. It checks FULL
-> 60 live ROTATION_ONLY frames -> FULL with no inserted NONE, source ownership,
fresh sample age, null position, rotating quaternion, stable calibration/history,
no per-frame topology rebuild, NONE suppression, duplicate/metadata immunity,
transport timeout and recovery. Existing Main/Fallback and desktop integration
tests remain applicable to other policy branches.

`MtpHilCaptureTests` validates the diagnostic logs against the real runtime,
including timeout, duplicate rejection and mismatched tracker isolation. A
separate JVM tests both diagnostic UDP ports, config preservation and refusal to
overwrite an existing capture. Only ephemeral ports and synthetic packets are
used in these automated tests.

Validation commands use JDK17 and the repository wrapper in an isolated local
clone, preserving the running HIL executable and local config/status/logs:

```powershell
.\gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --no-daemon --console=plain
```

Results: core **589/589 PASS**, desktop **9/9 PASS** (zero failures, errors or
skips); shadowJar and separate-process E2E **PASS**. The core tests ran in the
initial invocation; the desktop test helper initially failed compilation because
Jackson was not on that module's test classpath. Switching diagnostic JSON to
the already pinned Gson test dependency fixed it; the complete command then
passed, with unchanged core tasks up-to-date. No dependency was added. Diagnostic
classes were also verified absent from the production shadow JAR.
The explicit Gradle `mtpHilCapture` task also passed an empty-input, one-second
startup/shutdown smoke on ephemeral ports. This is software validation, not HIL.

Bridge isolated CMake Release configure/build **PASS**, complete CTest **18/18
PASS**, including `rotation_only_downstream`, Direct mock and separate-process
UDP. A diagnostic capture by itself is never a hardware PASS.

## Next manual HIL capture

The existing Bridge mirror on 29813 provides accepted Common Observation from
29810; it is not a raw ingress sniffer and can omit rejected/dropped packets.
Health JSON is only a 500ms diagnostic snapshot, insufficient for proving every
transition. The new explicit `mtpHilCapture` task records this mirror, actual MTP
datagrams on 29811 and the actual MonakaRuntime effective constraints together.
It uses only test-classpath code, absent from the production JAR. It adds no wire
fields and does not change any runtime, profile or config file.

This is a **standalone diagnostic MonakaRuntime**, not an observer of an already
running VRServer. It has no Slime/HMD inputs, no IK and no SteamVR connection.
Existing software IK/output tests are separate evidence; full interactive
VRServer/SteamVR HIL remains **NOT RUN**. Disk/UDP overload can make a diagnostic
capture incomplete; do not infer missing NONE packets from an incomplete trace.
It records all received packets, changing constraint snapshots, diagnostics and
source sequences; it never evaluates hardware success automatically.

Preconditions: user starts the already verified VIVE Backend and Bridge using
the intended HIL config and `monaka` or `both` policy. Close the MonakaVR receiver
and any observation-mirror viewer manually before capture (29811 and 29813 must
be free). Keep the Bridge/VIVE processes running so their session/map revision
does not change merely for capture. This task does not stop or restart them.

Run the following in PowerShell. It refuses an incompatible config, uses only a
new diagnostic config copy, and selects the exact current mapping for this HIL
device. Review the printed identity before physically exercising the tracker.
If the RF address changed, stop and review the mapping instead of substituting
a slot. If input revision/space changed, use the existing explicit approval UI;
do not auto-approve it in this helper.

```powershell
Set-Location 'C:\Users\nynyp\Downloads\6Dof\MonakaVR'
$ErrorActionPreference = 'Stop'
$bridge = Get-Content '..\MonakaBridge\config\bridge.json' -Raw | ConvertFrom-Json
$hilMappings = @($bridge.mappings | Where-Object { $_.source_id -eq 'vive-local-1' -and $_.device_id -eq '23:34:e4:5a:fe:39' })
if ($hilMappings.Count -ne 1) { throw 'Expected one explicitly configured VIVE mapping; review current device identity.' }
$mapping = $hilMappings[0]
$hilProfile = $bridge.profiles.PSObject.Properties[$mapping.profile].Value
if (-not $mapping.space_approved -or -not $hilProfile.approved -or -not $hilProfile.evidence -or $bridge.policy -notin @('monaka','both')) { throw 'Bridge output/profile/space gate is not approved.' }
$cfg = Get-Content '.\server\desktop\monaka-mtp.json' -Raw | ConvertFrom-Json
if ($cfg.version -ne 2 -or $cfg.assignments.Count -ne 1 -or $cfg.assignments[0].body -ne 'HIP' -or $null -ne $cfg.assignments[0].rotationFallbackTracker) { throw 'Expected one HIP Main with no external fallback.' }
if ($cfg.space.id -ne $mapping.world_space -or $cfg.space.revision -ne $mapping.world_revision -or $cfg.space.convention -ne 'rh_y_up_neg_z_forward' -or $cfg.port -ne 29811 -or $cfg.timeout_ns -ne '500000000') { throw 'World/port/timeout mismatch; review config explicitly.' }
$occupied = @(Get-NetUDPEndpoint -ErrorAction Stop | Where-Object { $_.LocalPort -in @(29811,29813) })
if ($occupied.Count) { $occupied | Format-Table LocalAddress,LocalPort,OwningProcess; throw 'Close the conflicting consumer manually before HIL.' }
$cfg.assignments[0].mainTracker.publisher_id = $bridge.bridge_id
$cfg.assignments[0].mainTracker.source_id = $mapping.source_id
$cfg.assignments[0].mainTracker.tracker_id = $mapping.tracker_id
Write-Host ('Diagnostic HIP Main: {0} / {1} / {2}' -f $bridge.bridge_id,$mapping.source_id,$mapping.tracker_id)
$run = Join-Path (Get-Location) ('build\hil-rotation-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run | Out-Null
$configCopy = Join-Path $run 'monaka-mtp.json'
$cfg | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $configCopy -Encoding UTF8
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :server:desktop:mtpHilCapture "-PmonakaHilConfig=$configCopy" "-PmonakaHilOutput=$run\capture" -PmonakaHilSeconds=90 -PmonakaHilMirrorPort=29813 --no-daemon --console=plain --quiet
if ($LASTEXITCODE -ne 0) { throw "Capture failed; inspect $run" }
Write-Host "Capture saved: $run\capture (manual review required; not a hardware PASS)"
```

After `READY`, perform and record the wall-clock order of these physical actions:

1. Obtain FULL tracking and gently rotate the tracker.
2. Reproduce the previously observed **post-localization** optical loss while
   rotating. Do not confuse first-localization NONE with ROTATION_ONLY.
3. Reacquire tracking and continue rotating. Repeat FULL -> ROTATION_ONLY -> FULL.
4. During live ROTATION_ONLY, stop packet publication without changing config;
   keep capture running for at least one second to observe transport timeout.

Save the whole capture directory (`capture.json`, `observation.jsonl`,
`mtp.jsonl`, `constraints.jsonl`), the diagnostic config copy, Bridge/backend
logs and the physical action times. Capture manifest stores the config hash.
Join MTP `input.source_id/device_id/session_id/sequence` to Observation, then MTP
`publisher_id/source_id/tracker_id/session_id/sequence` to `accepted_samples` in
the constraint log. Mirror and MTP are asynchronous; use provenance rather than
assuming line-by-line or clock-epoch alignment. Quaternions on either side of
calibration need not have equal components; compare sign-invariant angular
deltas within each coordinate stage and apply the selected transform for cross
stage comparisons.

Manual downstream PASS requires no intervening NONE in a complete FULL ->
ROTATION_ONLY -> FULL trace, null MTP position/false validity/zero confidence,
live orientation/degraded status, HIP position absent with Main-owned live
rotation, then position recovery and continuous rotation. With packet silence,
rotation must disappear after the configured sample-age timeout. `UnassignedPose`,
space mismatch, sequence gaps or queue overflow must be explained before PASS.
No RF persistent identity, mixed PICO/IMU FBT, first-pairing, world-calibration
redesign, release cutover or interactive SteamVR guarantee follows from this run.
