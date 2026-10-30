# Verification lanes — MonakaVR consumer

## Goal and boundaries
MonakaVR extends SlimeVR with producer-independent 6DoF body constraints, IK, Main/Fallback, and virtual tracker output. It is not a hardware-quality certification service. The runtime product policy migration is tracked at MonakaVR/MonakaBridge#17; this branch only changes which checks are reported as product software verification.

## Commands
- `powershell -File scripts/test_revision.ps1 -Mode Quick`: selected real Core code with synthetic poses, Main/Fallback, freshness, MTP input, and positional IK tests.
- `powershell -File scripts/test_revision.ps1 -Mode Full`: complete Core/Desktop JUnit tests, JAR build, and synthetic external-JVM/UDP MTP → private IK → computed tracker → captured production Protobuf path.
- `powershell -File scripts/test_revision.ps1 -Mode Audit`: historical complete software suite **plus** release-tooling and protocol-kit verification. For explicit source/release/compatibility review, not for every functional edit.

Quick/Full do **not** boot the real input producers, HMD, or SteamVR. Audit likewise does not establish hardware correctness. Current-policy unit tests remain in Full until issue #17 updates their runtime semantics; a Full PASS today is not proof that the intended new calibration policy is implemented.

## Verification policy
Keep product tests that assert numeric behavior, tracker selection, positional IK, output, lifetime safety, loss/recovery, and feedback exclusion. Do not equate a Git source hash, immutable historical file, proof-of-approval, or release receipt with tracking correctness. Release artifact checks remain separate. A meaningful end-to-end claim must name its actual boundaries and any mocked pieces.

Runtime and protocol changes must update affected functional test expectations **and** explain which historical audit expectations are superseded. Do not add an additional source-byte scope layer merely to preserve a prior temporary task rule. Report `NOT RUN` for Windows/SteamVR/hardware tests not actually executed.
