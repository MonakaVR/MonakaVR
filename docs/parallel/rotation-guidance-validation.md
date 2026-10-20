# Body rotation guidance — Stage A1 validation receipt

Date: 2026-10-21 (Asia/Tokyo).
Repository: MonakaVR/MonakaVR.
BASE_SHA: `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589` (fetched origin/dev).
Branch: `feature/monaka-body-rotation-guidance`.
The final HEAD_SHA and publication status are recorded in the local post-commit
receipt `build/rotation-guidance-final-receipt.json` (not a self-referential commit
hash). Push was rejected by automatic approval review because trusted user content
did not establish destination-specific authorization/ownership. Publication and
the dev-targeted Draft PR await explicit user approval; no PR URL exists yet.

## Final executed validation

JDK: Microsoft OpenJDK 17.0.20.1, Gradle wrapper 8.14.4, Windows PowerShell.
Fixed SolarXR submodule: `00c38a6dc28070b30850a89c26b17928e56245d4`.
Protocol upstream verification: PASS, unchanged pinned v2.1 artifact/content tree.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :server:core:test --console=plain
```

The final invocation also included `:server:spotlessKotlinCheck` with
`-PspotlessIdeHook=<three absolute new Kotlin paths, comma-separated>`.
Spotless's IDE hook reports `IS CLEAN` for each of the three files; its wrapper
check task is marked SKIPPED by that hook. No repository-wide formatting was run.

| Final check | Executed result |
|---|---|
| Core full suite | PASS: 1,975 tests, 0 failures/errors/skips, exit code 0 |
| New BodyRotationGuidanceTests | PASS: 35 tests, 0 failures/errors/skips |
| Existing HipRotationCorrectionTests | PASS: 22 tests |
| Existing RecoveryAngularDynamicsTests | PASS: 4 tests |
| Existing HybridFoundationTests | PASS: 3 tests |
| Existing MainFallbackPolicyTests | PASS: 7 tests |
| New Kotlin formatting | PASS: three IS CLEAN receipts, exit code 0 |
| git diff --cached --check | PASS |
| Physical calibration / hardware / HIL / SteamVR quality | NOT RUN |
| Production activation / Stage A2 runtime wiring | NOT RUN / OFF / DEFERRED |

Counts are read from the final Gradle JUnit XML, not inferred from annotations.
Local detailed evidence: `build/core-test-verified.log`, `build/core-test-exit.txt`,
`build/core-test-totals.json`, `build/core-test-suites.json`, and
`server/core/build/test-results/test/`. Initial fixture expectations were corrected
for the configured rate cap near pi, finite exponential residual and capability
constructor validation; final results above are from the complete rerun.
An earlier sandbox wrapper attempt could not write Gradle's cache; the actual
validation ran with permission to use that cache. This is not a missing test run.

## Delivered scope and remaining gates

New API: `BodyRotationGuidance.advance`, explicit immutable `BodyRotationState`,
`GuidanceBinding`, exact epoch/frame approval and independent receipt/evidence
inputs, `BodyRotationResult` with separate learning/application reasons and an
owner-qualified rotation-only candidate. `RotationGuidanceMath` exposes pure
normalization, SO(3) angle, frame composition, target error and bounded learning.
The numerical explanation and all threshold meanings are in
[rotation-guidance-core.md](rotation-guidance-core.md).

Only `tracking/correction/`, its tests and these documentation files are added.
Existing HIP/Direct/Hybrid/IMU-only implementations, config, GUI, protocol, bridge,
backend, startup flags, output IDs/roles and skeleton/calibration objects are
unchanged. MainFallbackPolicy is reused. No second learner is installed in a
runtime, no solver is replaced and Production Position Correction remains OFF.

READY FOR INTEGRATION / PRODUCTION OFF / HIL NOT RUN — Stage A1 core review only.
Stage A2 is deferred: a complete production supplier for approved non-HIP frames
and both independent acceptance receipts has not been demonstrated. Synthetic
evidence DTOs do not resolve that gate. Physical drift, latency, mount alignment
and angular-rate limits have no PASS claim. See [the audit](rotation-guidance-gap.md)
for the proposed later Phase 1 migration boundary. No merge is performed.
