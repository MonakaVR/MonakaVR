# Hybrid tracking foundation

This is a foundation, not completed continuous Hybrid tracking or an HIL PASS.
Work continues on `feature/direct-6dof-output` from
`fcdf98bca8e8d1ecb68150f0a417b2564ea6d567`. The feature worktree was clean at
entry. The separate live HIL checkout, local configurations, logs, installed
driver and running executables were not changed. No other repository is changed.

## Audit and design decision

The Direct baseline selected solver participation with `outputMode == IK`.
This excluded Direct-visible Main constraints from the existing skeleton.
Composition also ran before `HumanPoseManager.update`, so it could not read a
current-tick background result. Those two boundaries are corrected without a
second solver, changes to source selection, or protocol changes.

Previous flow:

```text
resolver -> EffectiveConstraint -> either IK writeback or Direct output
```

Current flow:

```text
MTP / Slime -> existing admission, freshness and Main/Fallback resolver
  -> ResolvedTrackingPose (selected components, owners, age, quality, space)
     +-> IK constraint adapter -> ConstraintIkWriteback -> existing HumanSkeleton
     |     -> HumanPoseManager.update -> current computed tracker
     |        -> BackgroundIkPoseReader -> BackgroundIkAlignment
     +-> OutputContinuityController <------------------+
           -> OutputPose -> stable virtual tracker -> existing Protobuf bridge
              -> compatible Direct driver -> existing driver universe transform
```

The server retains intake before IK. A new optional `afterPoseUpdate` hook runs
after the existing solver and before bridge writes. Previous visible samples
are cleared before solving; one pending frame is consumed once by the output
hook. Disabled Monaka installs neither hook. Hybrid requires the post-IK hook.

## Responsibility boundaries

| Responsibility | Implementation / constraint |
|---|---|
| Source eligibility and ownership | Existing `MainFallbackPolicy` and `ConstraintResolver`, unchanged; no second selector |
| Solver participation | Assignment `useAsIkConstraint`, independent of visible output |
| Main correction | `ResolvedTrackingPose.ikConstraint()` into existing `ConstraintIkWriteback`; Main FULL position and rotation continue to constrain the skeleton during Direct output |
| Background pose | Existing computed tracker after the current `HumanPoseManager.update`; no separate skeleton or estimator |
| Dynamic validity | `ResolvedTrackingPose` / `OutputPose`: nullable components with explicit validity, modality, owners, per-component observation time and quality; zero is not an invalidity sentinel |
| Continuity | Stateful `OutputContinuityController`; never decides raw source eligibility |
| Alignment | `BackgroundIkAlignment` converts skeleton world to the exact configured Monaka space/revision; default is unverified/unavailable |
| Output identity | One object/ID/body role and `monaka-direct:resolved-v1:<BODY>` serial per configured output, retained through transitions |
| Diagnostics | Discrete state/owner/modality/validity/availability/reason changes only; changing numeric poses and timestamps do not emit per-frame logs |

Main FULL owns both selected components. During loss, the existing resolver
still decides rotation ownership. `rotationFallbackTracker` remains a rotation
source only. Background IK is a separate pose source: the foundation uses its
current position with the resolver-selected rotation. It does not promote a raw
rotation fallback into a position tracker. If all selected rotation disappears,
Hybrid output becomes unavailable even if the skeleton has numeric storage.

Main constraints exercise the existing body position/orientation correction
path. Other IMU inputs remain live. The configured same-body rotation fallback
does not become a second simultaneous solver input while Main FULL owns rotation.
Continuous IMU drift estimation and world/root alignment calibration are not
implemented by this change.

## Continuity state and limitations

| State | Foundation behavior |
|---|---|
| `MAIN_DIRECT` | Exact resolved Main FULL output while IK continues in the background |
| `FALLBACK_ACTIVE` | On loss, use current aligned background position when available and keep resolver-selected rotation |
| `REACQUIRING` | Main FULL has returned after positional output/loss; retain current background position pending a configured convergence strategy |
| `UNAVAILABLE` | No usable selected rotation, or pause; no fabricated valid origin/held pose |

The controller stores transition time, last output, Main/fallback poses, blend
progress and position/rotation residuals. It receives the server clock, with no
sleep or independent freshness timer. A deterministic injected strategy test
exercises convergence completion back to `MAIN_DIRECT`.

**Production convergence is deliberately not configured.** After loss, Hybrid
can remain `REACQUIRING`; it does not automatically return to direct Main output.
No production blend curve, hysteresis duration, rotation convergence, velocity
matching or drift estimator is claimed. Rotation follows the selected resolver
component immediately. Background IK itself can move when constraints return;
remaining on that result is not a guarantee of smooth reacquisition. Direct mode
retains its original immediate component behavior and does not borrow IK position.

Background availability requires an enabled solver, an available positional
head/root anchor, finite computed pose, exact confirmed alignment and the current
tick. An old solver frame, mismatched space/revision, pause or unanchored skeleton
cannot supply position fallback. This conservative root check does not prove
physical pose accuracy; loss without a usable aligned anchor remains unavailable.

The audit found `Tracker.hasPosition/hasRotation` are constructor capabilities
used in `IKChain` topology. Changing their global meaning would exceed this
foundation. Existing private proxy reconstruction on capability transitions
therefore remains. Calibration retention is regression-tested; repeated same-mode
frames do not rebuild topology. Output registration is independent of that
private solver topology. Eliminating proxy reconstruction is deferred.

## Configuration and coordinate spaces

Config version remains 2. Existing assignments without new fields retain
`outputMode: ik`, participation enabled and continuity disabled. Existing Direct
assignments now participate in IK by default; an explicit `useAsIkConstraint:
false` preserves the earlier output-only choice.

For an explicitly opted-in Hybrid assignment, add these fields while preserving
its real Main/fallback identities and body:

```json
{
  "outputMode": "hybrid",
  "useAsIkConstraint": true,
  "continuity": "background_ik"
}
```

Hybrid requires participation. Direct/IK accept `continuity: none`; incompatible
combinations fail validation. Changing configured visible output assignments or
strategy requires restart. Save/load preserves all fields. No live config is
migrated by this task.

Fallback alignment is unavailable by default. Only after independently checking
that skeleton world and the configured Monaka space are actually identical may
an operator supply this root-level assertion (use the real exact space/revision):

```json
{
  "backgroundIkAlignment": {
    "kind": "confirmed_same_space",
    "space": {
      "id": "<configured Monaka space>",
      "convention": "rh_y_up_neg_z_forward",
      "revision": 0
    }
  }
}
```

This asserts identity, not calibration evidence. Similar coordinate numbers do
not authorize alignment. A future calibrated transform belongs in
`BackgroundIkAlignment`, not in the resolver or codec. Direct Main and aligned
background meet in configured Monaka space. Protobuf carries that output to the
existing driver input; the existing driver universe transform remains the
SteamVR-space boundary. Physical Monaka-to-SteamVR alignment is NOT RUN.
The existing Direct-driver compatibility handshake remains mandatory for both
Direct and Hybrid. No wire/schema, codec, ports or vendor conversion changed.

## Requirement audit and software evidence

| Requirement | Status and evidence |
|---|---|
| Independent output and solver participation | Implemented; Direct and Hybrid default to participation, explicit Direct opt-out tested |
| Main FULL plus live background IK | Implemented; real HumanPoseManager test observes changed computed HIP while visible output equals Main and another IMU remains attached |
| Main/Fallback / legacy / freshness | Existing selection unchanged; full Core suite, config defaults, resolver ownership and 500ms expiry pass |
| Dynamic validity and no invalid zero | Implemented in typed output and existing optional XYZ serializer; unavailable components remain absent |
| Same-tick fallback / space gate | Implemented; old frames, space/revision mismatch, unverified alignment and invalid root are rejected |
| Stable identity / no feedback | Implemented; same virtual object, ID, serial and role through loss/recovery, output rejected as input |
| Continuity and correction interfaces | Foundation; state, residual/time storage, strategy injection and existing solver correction seam implemented |
| Smooth loss/recovery / continuous drift correction | Not implemented or claimed; requires production strategy and HIL |
| Permanent solver capability topology | Deferred; existing private proxy rebuilding remains, calibration retention tested |
| GUI / auto calibration / multi-6DoF fusion / prediction | Not implemented in this task |

New suites:

- `OutputContinuityTests`: five deterministic tests covering state transitions,
  selected ownership, current-frame/space rejection, pause, pending and injected
  convergence, first acquisition, and change-only diagnostics.
- `HybridFoundationTests`: two tests covering real IK/computed numeric updates,
  live IMU inputs, modality/calibration behavior, stable output, freshness,
  feedback exclusion and backward-compatible config.
- `HybridServerIntegrationTests`: two tests covering production pre/post hooks,
  current-tick composition, stable output, lifecycle and fail-closed alignment.

Existing Direct tests retain component/serialization checks and now request an
explicit solver opt-out where they exercise output-only mode. Feature-OFF tests
also reject installation of the new hook. No tests were removed.

Executed in the isolated feature worktree with JDK 17:

```powershell
Set-Location 'C:\Users\nynyp\Downloads\6Dof\MonakaVR\build\direct-6dof-worktree'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Hybrid software validation failed' }
```

Results: Core **599**, Desktop **14**, zero failures/errors/skips; shadowJar
**PASS**; existing separate-process UDP MTP E2E **PASS**. E2E covers the existing
intake/IK lifetime path; Hybrid transitions specifically use the real-IK and
production-hook suites above. The E2E result is not physical continuity evidence.
Fresh JVM FULL/IMU/NONE/FULL captures also passed the existing isolated native
decoder/OpenVR conversion test. Native code and its prior build were unchanged;
this was an interop rerun, not a new native build or live OpenVR invocation.
Detailed local logs and final-HEAD evidence are under ignored `build/`.

Hardware/HIL, Direct SteamVR quality, physical position continuity,
reacquisition smoothness, hysteresis feel, SteamVR dynamic validity and world
alignment accuracy: **NOT RUN**. No install, live runtime restart, merge or push
is performed by this task.
