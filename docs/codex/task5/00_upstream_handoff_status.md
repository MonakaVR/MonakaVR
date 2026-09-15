# Task 5 upstream handoff status

This file records the upstream state accepted for Task 5 preparation. It is a gate, not permission to invent missing artifacts.

## Task 1 — MonakaProtocol

Accepted fixed contract inputs:

- repository: `MonakaVR/MonakaProtocol`
- source commit: `5f41586b51bd84bb1cea344879b5d6325fc2c47d`
- schema commit: `04f2d6c831c68a65edfbfb66ff8838d1c9d78535`
- `monaka-protocol-kit-v1.0.zip` SHA256: `eef5b7f2bc490926385b99dabcd44dc5a374228bf2a7869beea01f9dad936729`
- `protocol.lock.json` SHA256: `234f0dffc46b808a179ec91da3c185794d7b7c83bc5b7d2bcb31fa73886bcb44`
- C1 text SHA256: `3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3`
- status: `candidate / master reconciliation pending`

The Task 1 JVM codec/API is normative. Task 5 must not implement a substitute codec or change C1 locally.

If the actual Task 1 artifact is not supplied to the Task 5 workspace, do not claim protocol integration complete.

## Task 2 — PICO Backend Phase A

Accepted software handoff source:

- repository: `orzkwsk/PicoMotionTrackerBridge`
- branch: `refactor/monaka-layer-separation`
- HEAD: `c638f158516effd7fc6511daf179b9a927a099a6`
- status: `Phase A complete / Phase B pending`

Task 2 retained the legacy PICO receiver/SteamVR paths while adding C1 Observation publication. Task 2 Phase B must not begin merely because Task 4 exists. It also requires Task 5 final evidence that the old PICO receiver C ABI is no longer used by MonakaVR, plus the planned compatibility/hardware cutover gates.

Task 5 does not consume POTB or the old PICO C ABI as its new input.

## Task 3 — VIVE Backend

Accepted software handoff source:

- repository: `orzkwsk/ViveUltimateTrackerBridge`
- branch: `refactor/monaka-layer-separation`
- HEAD: `eaaee63fcacb76d095ff743944fbff1f1427f015`
- status: `software implementation complete / hardware validation NOT RUN`

Important unresolved hardware facts remain: official dongle with Hub stopped, physical axes/scale/quaternion, loss/recovery, map reset and multiple trackers. Therefore Task 5 must not compensate for an assumed VIVE native profile. MonakaBridge owns profile approval/calibration; `vut-native-v1` is not evidence for a second transform in MonakaVR.

## Task 4 — MonakaBridge

Accepted source state after software audit:

- repository: `MonakaVR/MonakaBridge`
- branch: `refactor/monaka-layer-separation`
- accepted HEAD: `acc329dce90dd6ba21387029cd54b6fc2d82fe8c`
- status: `software implementation complete / hardware cutover pending`

Relevant implementation commits after initial artifact import:

- `b8e8daf5ee9bcbdad42afa6a955e137844a6a691` — common C1 registry, calibration, mapping, bounded routing
- `12f9ad853d680ab4389dd1e67a64738e9b3418d9` — migrated common SteamVR output/calibrator/WPF controls
- `597c526c708c6d698a61154972a4d82b38430c7a` — isolated OpenVR regression context and cross-process C1 pipeline verification
- `acc329dce90dd6ba21387029cd54b6fc2d82fe8c` — crash root-cause/cutover/handoff documentation

### Direct regression crash disposition

The earlier `direct_regression.exe` null-read Access Violation was not accepted as PASS. Task 4 reproduced it against the unchanged, hash-verified Task 2 `TrackerDevice`.

Root cause: the old standalone test invoked `TrackerDevice::RequestOrientationZero()` without an OpenVR driver context. `vr::VRDriverLog()` dereferenced the missing context before the apparent logger null check could protect the call.

Task 4 retains an expected-fault reproduction requiring Windows `0xc0000005`, read operation, address zero, with stack evidence through `vr::VRDriverLog` and `TrackerDevice::RequestOrientationZero`. The repaired standalone Direct regression supplies a test-only `IVRDriverLog` driver context and rejects unexpected runtime-interface access. The repaired test does not load the SteamVR runtime.

Do not reintroduce the old context-free harness assumption in Task 5.

### Task 4 software verification structure

The committed verification script is designed to record separate results for:

- old null-read expected-fault reproduction
- repaired standalone Direct regression
- DLL dependency check
- Release CTest
- GUI configuration smoke
- separate-process loopback UDP regression
- fixed Task 1 codec interoperability
- derived provenance verification
- common-pipeline performance measurement

It starts validation in RUNNING state and does not carry an earlier PASS forward. Hardware remains explicitly `NOT RUN`.

### Task 4 generated handoff required by Task 5

Expected generated files:

- `monaka-bridge-handoff.zip`
- `monaka-bridge-handoff.handoff.json`
- `task4-final-report.json`

These generated `dist/` outputs are not committed in the accepted Task 4 Git tree. Their exact SHA256 values are therefore **not recorded here and must not be guessed**.

When real Task 4 handoff files are supplied to the Task 5 workspace:

1. verify their actual SHA256 values;
2. verify the external manifest/report says source commit exactly `acc329dce90dd6ba21387029cd54b6fc2d82fe8c`;
3. verify the package content manifest and validation evidence;
4. record the accepted Task 4 artifact SHA256 in the Task 5 final report.

If these files are absent, Task 5 may inspect/refactor its own existing runtime and build generic scaffolding, but must not invent the handoff hash or call Task 4 integration fully verified.

## Task 4 → Task 5 runtime contract

MonakaBridge owns:

- Observation ingress on loopback `29810`
- physical→logical mapping
- coordinate profile approval
- shared calibration/mount transforms
- mapping revision
- normalized MTP publication on `29811`
- common Direct feed on `29812`
- Observation mirror on `29813`
- generic SteamVR Direct driver/GUI/calibrator

Task 5 receives normalized MTP and owns body assignment/Fusion-Fallback/IK/solver output.

Task 5 must not apply old PICO/VIVE coordinate mapping to MTP again.

## Feedback exclusion gate

Task 4 Direct runtime serials use the `monaka-direct:` namespace. Task 5 must additionally use a distinct namespace for solver/virtual-tracker output.

Neither of those outputs, nor Task 5 private IK proxy inputs, may be reintroduced into the raw Observation/MTP input set. Add an explicit regression showing Bridge output policy `both` does not create a self-feedback loop.

## Hardware status

Task 4 software completion does **not** authorize physical cutover. At preparation time these remain independent release gates unless later evidence is supplied:

- PICO physical tracker set / five-tracker behavior
- VIVE official dongle with Vive Hub stopped
- VIVE physical axes/scale/quaternion/map semantics
- PICO + VIVE coexistence
- actual SteamVR driver registration/restart/routing
- same-point physical Direct vs MTP comparison
- interactive WPF/tray behavior
- OpenVR calibrator measurement

Task 5 may complete its software DoD with mock/in-memory integration while reporting these as `NOT RUN`. Do not label them PASS without actual hardware execution.

## Task 2 Phase B handoff requirement

Task 5 final report must provide Task 2 with:

- exact Task 5 final HEAD
- build/test evidence
- proof that the old PICO receiver C API/JNA carrier is not an active dependency of the new MonakaVR path
- configuration migration notes
- self-feedback exclusion result
- remaining hardware/cutover gates

Only after those gates and the planned compatibility checks should Task 2 remove its legacy consumer/fan-out path.