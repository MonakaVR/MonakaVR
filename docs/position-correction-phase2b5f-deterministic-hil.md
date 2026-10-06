# Phase 2B-5F: deterministic OpenVR HIL action preparation

This phase adds an isolated Windows x64 test client in `native/hil`. It is not
linked to the driver, installed, or started automatically. Production event
mapping, universe caching, conversion, protobuf and server behavior are unchanged.
HIL results must be reported separately from software tests.

## Read-only feasibility audit

Audited source: driver `dcc0f56bcb2a3196d6f92b1ed1d029faa425b931`, OpenVR
`91825305130f446f82054c1ec3d416321ace0072`, linalg
`a3e87da35e32b781a4b6c01cdd5efbe7ae51c737`. Paths below are relative to the
pinned driver's `libraries/openvr` directory, not the server bindings provider.

| Pinned interface and exact operation | Semantics / disposition |
| --- | --- |
| `IVRChaperone_004`: `virtual void ResetZeroPose(ETrackingUniverseOrigin eTrackingUniverseOrigin) = 0;` (`headers/openvr.h:3334`) | Sets the selected origin to current HMD position/yaw, keeps world Y up. Header warns that it overrides the user's saved zero pose and must follow a user action. Returns **void**, so there is no success/error/effect acknowledgement. Supports an origin argument; runtime-specific behavior requires HIL. |
| `TrackingUniverseSeated = 0`, `TrackingUniverseStanding = 1`, `TrackingUniverseRawAndUncalibrated = 2` (`openvr.h:353`) | Seated is relative to seated zero; standing to configured bounds; raw is provider-defined. Helper resets only seated/standing, never raw. Neither reset is assumed non-persistent or automatically reversible. |
| `IVRSystem_023`: `virtual void GetDeviceToAbsoluteTrackingPose(ETrackingUniverseOrigin eOrigin, float fPredictedSecondsToPhotonsFromNow, TrackedDevicePose_t *pTrackedDevicePoseArray, uint32_t unTrackedDevicePoseArrayCount) = 0;` | Client pose query, distinct from server-driver raw query. Used at initialization to fail closed on disconnected/invalid HMD. Zero prediction is a software query, not proof of physical acquisition time. |
| `virtual HmdMatrix34_t GetSeatedZeroPoseToStandingAbsoluteTrackingPose() = 0;` / `virtual HmdMatrix34_t GetRawZeroPoseToStandingAbsoluteTrackingPose() = 0;` (`openvr.h:2397,2401`) | Read-only client transforms, recorded as exact binary32 matrices. Two calls are not an atomic snapshot. No cache/live equivalence claim. |
| `IVRSystem::ResetSeatedZeroPose` | No callable member in this pinned header. The name occurs only in commentary. Do not use historical APIs from memory. |
| `IVRChaperoneSetup_006`: `virtual bool CommitWorkingCopy(EChaperoneConfigFile configFile) = 0;` | Saves working copy to disk. Excluded from helper. |
| `virtual void RevertWorkingCopy() = 0;` | Reverts **working copy** to live calibration; does not undo a reset or restore a prior live origin. Excluded. |
| `virtual void SetWorkingSeatedZeroPoseToRawTrackingPose(const HmdMatrix34_t *pmatSeatedZeroPoseToRawTrackingPose) = 0;` / `virtual void SetWorkingStandingZeroPoseToRawTrackingPose(const HmdMatrix34_t *pmatStandingZeroPoseToRawTrackingPose) = 0;` | Working-copy mutation, followed by a commit for live/disk effect. No room editor or rollback mechanism added. |
| `SetWorkingPlayAreaSize`, `SetWorkingCollisionBoundsInfo`, `SetWorkingPerimeter`, `ImportFromBufferToWorking`, `ReloadFromDisk`, `RoomSetupStarting` | Room/bounds/configuration operations excluded. No erase, import, commit, preview, reload or universe-cache operation invoked. |
| `IVRCompositor::SetTrackingSpace(ETrackingUniverseOrigin eOrigin)` | Selects compositor client's tracking space; not a global origin-reset operation. Excluded. |
| `IVRServerDriverHost::GetRawTrackedDevicePoses(float fPredictedSecondsFromNow, TrackedDevicePose_t *pTrackedDevicePoseArray, uint32_t unTrackedDevicePoseArrayCount)` (`headers/openvr_driver.h`) | Returns provider-defined driver raw space. Client reset semantics do not establish that this driver raw space changes; a no-effect result is a valid possible observation. |
| `VR_Init(EVRInitError *peError, EVRApplicationType eApplicationType, const char *pStartupInfo = nullptr)` with `VRApplication_Background = 3` | Client context, no server-driver context. Pinned enum says background clients should not start or keep SteamVR running. Initialization errors and missing `IVRChaperone_004` fail closed; no reset is requested. |

Feasibility: **A for supported external API dispatch; C for unapproved persistent
reset execution**. The API can operate while the HMD stays stationary; hardware
validity and stationarity are separate facts. A void return neither guarantees
event emission nor synchronizes runtime application with driver observations.
Mapped event constants include seated 804, standing 808 and universe 801; their
mapping is unchanged. Exact delivery and first-affected-pose order require HIL.

The audit found no pinned non-persistent reset API or supported atomic rollback
that meets the preferred reversible action criterion. Obtain explicit approval
for saved-zero-pose changes before invoking either reset. Standing reset remains
NOT TESTED unless separately authorized; do not infer safety from a seated test.

## Build and commands

Configure `native/hil` as a separate CMake project with a clean exact-pin
`OPENVR_SOURCE` checkout. Build Release and run CTest. The helper links only the
pinned `openvr_api.lib` and Windows `ole32`; its adjacent `openvr_api.dll` is copied
from that same read-only checkout. There is no install target.

```text
monaka_openvr_hil_action --help
monaka_openvr_hil_action --action inspect
monaka_openvr_hil_action --action mark --label tracking_cover_begin
monaka_openvr_hil_action --action mark --label tracking_cover_end
```

Only after explicit approval for the saved-origin change:

```text
monaka_openvr_hil_action --action seated-reset --allow-persistent-chaperone-change
```

Standing uses `--action standing-reset` with the same required flag. Each process
performs at most one explicitly requested action, then shuts down its OpenVR
client. No repeat loop, driver restart, room commit, or undo is implicit.
`--help`, invalid commands and missing confirmation never initialize OpenVR.
Manual markers work without SteamVR and identify only an operator observation.

## Logging and correlation limits

Stdout lines have stable prefix `MONAKA_HIL_ACTION_V1` followed by a JSON object.
Record kinds are `request`, `result`, and independent `pre_state` / `post_state`.
Each action uses a GUID helper owner and a monotonically increasing local
ordinal, combined as `helper_owner:ordinal`. A new process gets a new owner.
JSON escapes all control and non-ASCII bytes; labels are limited to 256 bytes.

Every record includes action ID/kind/ordinal, origin, monotonic ns, wall Unix ns,
Windows QPC ticks/frequency, API status and explicit identity/time disclaimers.
Wall Unix ns and QPC ticks are decimal strings to preserve integer precision.
The request record is flushed before dispatch. Logging failure prevents the
reset if it occurs before dispatch. Failure after dispatch cannot undo a reset;
missing result evidence is inconclusive. Runtime-unavailable results explicitly
say no action was performed and have no fabricated request/API return.

`void_return_no_success_status_or_effect_ack` means only that the void call
returned. It must never be counted as a confirmed successful reset without
independent runtime evidence. Source cannot supply the instruction's requested
API success boolean because no such result exists in the pinned API.

Clock reads are sequential, not an atomic wall/QPC calibration. The existing
driver trace carries software ordering and vrserver log timestamps, without the
helper QPC counter or helper ID. Do not assume identical cross-process steady
epochs or merge nearest timestamps as causal proof. Keep four separate timelines:
helper requests/results, driver mapped events, capture/binding, outgoing wire.
Initial matching is **candidate matching** using bounded stationary windows and
isolated repetitions; wall-time-only matching cannot be HIGH.

If retained evidence cannot bound the request/event/pose association, stop before
claiming exact attribution. Minimum next instrumentation proposal: a separately
reviewed, test-only action-intent channel recorded alongside driver observer
sequence and QPC under the existing probe mutex, plus low-overhead timestamped
capture/send records. Even that would identify software observations, not the
physical instant a reset is applied. No such channel is implemented in this phase.

## Pending HIL procedure

Before deployment, record installed, reviewed and backup DLL SHA-256, preserve
the original driver, obtain active-task permission for replacement/restart, and
verify installed reviewed bytes match. Use fresh disposable overlay, preservation
check, Release build, three native regressions and CTest. Never run the upstream
mock-suite because it can collide with the production pipe.

Enable only `MONAKA_HMD_FRAME_PROBE=1` and
`MONAKA_HMD_FRAME_PROBE_BINDINGS=1` in the launch process, leaving POSES unset.
Establish real HMD/provider identity, operator-confirmed stationary fixture and a
short baseline first. Then collect separate short pre/request/return/post windows
for at least five seated requests if authorized and behavior permits. Check
validity, raw/cache/wire separately, event delivery lag, same-universe behavior,
ordinal gaps, capture/bind boundaries and scheduling effects. Keep all ambiguous
candidates in machine-readable evidence and lower confidence accordingly.

Controlled movement and tracking-cover markers require actual operator actions;
software markers cannot certify movement, stationarity or tracking loss. Natural
universe changes are supplemental only. Do not change room setup to force one.
After HIL, restore original DLL and verify its initial hash, clear probe flags,
restart normally, verify HMD/driver/real-consumer reconnect and no helper left
running. A DLL restore does not restore saved zero poses changed by ResetZeroPose.

Evidence belongs only in ignored `build/deterministic-hil-2b5f-<timestamp>/`.
Server Gradle gates are not required solely by this independent helper: no server
source or build graph is changed. Report them NOT RUN, never carry prior PASS
forward. Hardware status remains NOT RUN until performed.

## Contract remains on hold

Deterministic helper markers are test-action observations, not frame epochs or
physical application timestamps. API request/return and SteamVR event observation
are distinct. Exact raw pose, applied cache and outgoing wire pose are evaluated
separately. Generation equality means only no OBSERVED boundary occurred between
capture and bind; inequality means this driver observed a boundary in that
interval, not that physical frame identity is known. Bind-to-send ordering is
limited to this probe's mapped event observer. Universe ID, probe owner, transport
session, server Tracker lifetime and helper action ID remain distinct identities.

No frame lineage is emitted over protobuf; no CoordinateSpace or HMD calibration
epoch is created. RawHmdPoseInput remains runtime-blocked. No
MainDecoupledHipPredictor or Position Correction runtime path is implemented.
MainFallbackPolicy, Rotation Correction, OutputContinuity, body-model publication,
universe cache semantics and HMD conversion formula are unchanged. Trusted frame
epoch is NOT YET TRUSTED; CoordinateSpace and RawHmdPoseInput are BLOCKED;
frame-lineage protocol remains HOLD until actual ordering evidence supports the
next phase. Software tests cannot change these decisions.
