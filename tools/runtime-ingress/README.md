# Bounded production MTP ingress for the accepted Core executable

This is an opt-in external ingress module. It does not change any Core/Fusion
source or the accepted `core.jar`. `MtpRuntimeIngress.receive` uses that JAR's
canonical `MonakaCodec`, `MtpInbox`, `MtpObservationBackend.poll`, and
`MtpPoseAdapter`. Both pose and tracker_state reach the backend. Present metadata
does not refresh pose age; lost/absent metadata can remove a sample. All counters
are the actual accepted backend counters. This Core lineage does not expose the
P1 reference lineage's cross-packet CapabilityConflict counter.

Capability normalization belongs to the pinned canonical MBIP P1 dependency in
MonakaBridge before publication. This tool implements no Normalizer, wire codec,
coordinate transform, calibration, recovery, or independent validity rule.
`RuntimeIngressMain` only transports JSONL carriers. `--input -` reads UTF-8 stdin
for an operator-owned stream; EOF cleanly stops the owned Core process. File input
is safe replay with no sockets or devices. Core frame templates must supply actual
authorized HMD/IMU inputs for physical HIL; synthetic fixtures are software-only.

Build with JDK 17 and the exact accepted Core package:

```text
python tools/runtime-ingress/build.py --core-package CORE_PACKAGE --jdk JDK17 --output build/runtime-ingress
java -cp "build/runtime-ingress/monaka-runtime-ingress.jar;CORE_PACKAGE/lib/*" dev.monaka.runtime.ingress.RuntimeIngressMain --package CORE_PACKAGE --config CORE_CONFIG --session NEW_SESSION --input CARRIERS.jsonl --output NEW_CAPTURE.jsonl
```

Each carrier has `envelope`, `received_at_ns`, `peer`, and `frame`. The configured
Main logical identity must match exactly, with explicit HIP assignment used only
inside this opt-in HIL ingress. Sequence/session/peer/space/mapping admission is
owned by the existing backend. A new session is permitted only by its existing
lease/retirement rules. The previous unavailable-carrier representation is reused
only after backend sample removal; its provenance is documented in
`UnavailableCarrier.java`. State is never passed to the pose-only `mainMtp` CLI.

`CoreProcess` refuses a mismatched accepted JAR hash and reused IPC directory,
launches no device client, sends only input/shutdown operations, and verifies
clean shutdown. Output is CREATE_NEW. The fixed Core/JAR remains
`c73805da1a64d0b161775decd60e5632ea295a0f`, SHA-256
`ef37474a804ea0c3705fe556d21c97861fc4c1fd8feb1b622c42130c8f061863`.
