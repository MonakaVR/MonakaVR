# Bounded production MTP ingress for the accepted Core executable

This is an opt-in external ingress module. It does not change any Core/Fusion
source or the accepted `core.jar`. `MtpRuntimeIngress.receive` uses that JAR's
canonical `MonakaCodec`, `MtpInbox`, `MtpObservationBackend.poll`, and
`MtpPoseAdapter`. Both pose and tracker_state reach the backend. Present metadata
does not refresh pose age; lost/absent metadata can remove a sample. All counters
are the actual accepted backend counters. The selected Core artifact determines available diagnostics. The original
accepted Core lacks cross-packet CapabilityConflict; the current static-capability
Core exposes it. These actual counters pass through unchanged.

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

## Current candidate packages

The default legacy mode retains the exact historical Core hash above. It never
silently accepts a new Core. For a reviewed current candidate, first build the
Core package from a clean committed checkout, record its source HEAD and every
`lib/*.jar` SHA256, then supply `--candidate-manifest FILE` to build.py.

The manifest has schema `monaka.runtime-ingress.candidate.v1`, repository
`MonakaVR/MonakaVR`, `source_commit` equal to that exact clean checkout HEAD, and
`libraries` entries with `path` (`lib/core.jar`, etc.) and `sha256`. The complete
inventory must match: missing, extra, foreign, dirty, or changed inputs fail.
The build compiles this identity into the ingress JAR. CoreProcess verifies every
library before creating its IPC session or starting its owned child. There is no
runtime expected-hash override, environment bypass, or fallback to old Core.
Keep the generated runtime-ingress-provenance.json with the candidate manifest.

Run `python tools/runtime-ingress/test_artifact_identity.py` (also under `-O`).
Physical operation still requires separate authorization and actual source-flow,
configuration and calibration preflight. This tool changes no Fusion/recovery,
wire semantics or Admission settings. Gate default OFF and `hil_ready=false`
remain until their separate gates are satisfied.

The current candidate uses the opt-in `CorePocHilStdioMain` transport. It calls
the same `CorePocHilControl.execute` and `CorePocHilSession` as file IPC; source
admission, validity, Fusion, recovery and tuning are unchanged. Legacy mode keeps
the original file IPC. The current candidate builder requires the stdio class
and compiles the transport choice into the ingress; there is no runtime override.

`live-mtp-carrier.py` stamps exact UDP receipt on a dedicated reader before
forwarding backpressure, retains every raw datagram, and uses a bounded queue.
Overflow or forwarding delay exceeding the existing 500ms freshness bound stops
capture visibly. It never clamps timestamps, rewrites validity, or silently drops
samples. A failed child/pipe still produces transport-result.json. This is an
explicit synthetic root/fallback diagnostic frontend, never independent IMU proof.
