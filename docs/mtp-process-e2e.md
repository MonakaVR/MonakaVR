# MTP v2 process E2E (software only)

Run with JDK 17, Python 3 and the repository wrapper:

```powershell
$env:JAVA_HOME = '<JDK17 directory>'
.\gradlew.bat :server:desktop:mtpProcessE2E --no-daemon --console=plain
# Also included after the ordinary unit/build tests:
.\scripts\test_revision.ps1 Full
```

The standalone task always executes. It creates a new directory under
`server/desktop/build/mtp-process-e2e/`. To choose the evidence location, pass
`-PmonakaE2EOutput=<new directory>`; reusing a directory fails rather than mixing
old PASS receipts with a new run. `result.json` records stages, process IDs and
explicit NOT RUN gates. `e2e.log` contains stage diagnostics, wire messages, IK
numerical results and output registration checks. Packet files and publisher
stderr logs remain alongside it. Console output ends with PASS/FAIL and the
stopping stage. Any failed check or child process failure returns nonzero.

## Actual path and boundary

The Gradle task launches a consumer JVM. That JVM starts persistent **separate
publisher JVMs**, each with its own loopback UDP socket. Scenario files are
decoded and encoded using the actual hash-pinned wire-v2 codec in the publisher;
no replacement wire encoder is used. The publisher acknowledges sending, while
the consumer independently waits for intake/admission results. A send ACK alone
is never treated as acceptance. The consumer uses the real monotonic clock,
500 ms lease/freshness rules and ephemeral ports. Receive/poll deadlines are
bounded; no hardware, reserved production ports or background services are used.

The consumer composes the production `MtpUdpReceiver`,
`MonakaServerIntegration`, `MonakaRuntime`, `ConstraintIkWriteback` and
`HumanPoseManager`/`HumanSkeleton`. It invokes the relevant VRServer order:
bridge read → registered before-pose hook → existing pose/IK update → bridge
write. It does **not** boot the whole desktop `VRServer`, GUI or native runtime.
The existing JUnit and release gates separately retain tick-order, fallback,
feedback-exclusion and packaged-JAR checks.

Registration follows the current architecture: logical source/tracker admission
under `(publisher_id, source_id, tracker_id)`, an assigned private IK input, and
computed output trackers registered through production `ProtobufBridge`.
Private inputs are not registered as public/raw Slime trackers. This test does
not claim discovery in the GUI or a SteamVR device registration.

Only the SteamVR transport is replaced by a test capture. Production
`addSharedTracker`, `dataRead`, `dataWrite` and serialization produce messages;
the capture parses their protobuf bytes and checks tracker IDs/`human://`
serials, positions and orientations against the computed trackers. A synthetic
read heartbeat permits the existing output pacing. No native SteamVR calls or
alternate solver are used. This verifies **output serialization before the
transport**, not named-pipe delivery or SteamVR rendering.

## Mandatory scenarios

- Feature OFF: external packets go to an isolated sink; no Monaka config/socket
  or hook is created. Thirty moving Slime input frames produce identical
  computed positions and rotations to an ordinary Slime composition.
- FULL: first admission creates the private input; thirty additional frames
  update constraints and numerically move the existing IK's computed hip.
  Output registrations remain stable and actual protobuf updates match.
- ROTATION_ONLY → NONE → FULL: positional input disappears, NONE removes both
  constraints, FULL recovers, and existing calibration survives.
- Duplicate/reordered poses are rejected without changing samples or age.
- Same tracker names under other publishers/sources remain distinct; a sibling
  tracker shares only the appropriate lifetime.
- Competing active sessions and same-session different UDP peers are rejected.
- Absent state removes the sample; present metadata cannot revive it; a pose at
  the absence watermark cannot recover it, but a newer pose can.
- An 800 ms old pose may remain in the diagnostic cache but is excluded from
  constraints/IK. Metadata and duplicate traffic cannot make it fresh.
- Real publisher exit (without a lost packet) removes usable constraints after
  the normal timeout. It does not erase identity/replay tombstones or falsely
  promise that all Slime computed output disappears.
- A new process/session recovers after lease expiry. Sequence restarts at zero;
  old-session replay fails; identity/calibration stay stable. The old lifetime's
  sibling samples clear while other source/publisher lifetimes remain intact.
- Receiver shutdown unregisters the hook, clears samples and releases its port.

`scripts/release_v2.py` also requires this task, retaining the result and detailed
log in the source-bound F10 archive. Generate release evidence from a clean
committed tree as described in [release-v2.md](release-v2.md). Historical evidence
is not reused. The runner sources are test-only and are not packaged into the
runtime JAR. Runtime/protocol/solver semantics and F12 are unchanged.

## NOT RUN and shortest human follow-up

SteamVR runtime/driver transport, GUI registration, HMD and PICO/VIVE hardware,
physical axes/scale and backend-vendor loss semantics are **NOT RUN**.
The external publisher is synthetic; it does not certify a real MonakaBridge
build or vendor backend.

1. With matching v2 artifacts, start one real backend → MonakaBridge and MonakaVR
   with MTP enabled; explicitly assign its full publisher/source/tracker identity
   as a Main tracker. Keep coordinate space/calibration aligned.
2. Start SteamVR with the existing driver/output configuration. Move that tracker
   and verify the assigned computed virtual tracker's position and orientation.
3. Stop the publisher and observe input loss; restart it, allow the 500 ms lease
   to expire, and verify recovery without duplicate devices or a reset jump.
4. Exercise ROTATION_ONLY/NONE only when the backend actually reports those
   states; do not infer hardware semantics. Disable MTP and confirm ordinary
   Slime tracking still behaves as before. Record hardware results separately.
