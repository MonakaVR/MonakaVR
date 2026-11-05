# Full Reset software acceptance

## Identity and ownership

- Task: FULL-RESET-FIX, OFFLINE, 2026-11-06; parent designated role Astra / High (actual parent runtime model not independently verified here); sole production implementation and software-test owner specified/used GPT-6.1 Sol / High (approved substitution for unavailable GPT-5.6 Sol / High). Spawn succeeded. Independent Sol reviewer is separate; no implementation sub-agent spawned.
- Worktree: `H:/tmp/6Dof/MonakaVR`; topic `fix/q-only-full-reset-20261106`; base `85a9ead351abec6f56de37e1f854bbe96801ae33`.
- Tests below cover the base plus the explicitly listed final patch. Parent G2 and independent review ACCEPT the scoped Full Reset correction, with the selected regression group remaining PARTIAL. Exact subsequent topic commit/build identity is recorded by standard build provenance and `Build/diagnostics/full-reset-fix/final-candidate.json`; no self-referential commit SHA is embedded here.
- Production: `ConstraintIkWriteback.kt`, `MonakaSolverComposition.kt` only. Tests: new `MvipQOnlyResetTests.kt`, strengthened `desktop/SolverCompositionIntegrationTests.kt`.
- Other repositories, MVIP wire/codec, solver mathematics, GUI request path and producer control were not changed. No production application or physical device was started or stopped.

## Behavioral red proof

Before production edits, the encoded public-fixture -> `MonakaRuntime` -> solver composition -> real `HumanPoseManager` test failed at the actual consumed Q boundary. An ordinary reset-aware Tracker under the same tilted Q and nonzero HMD yaw passed the independent expected head-yaw check.

Quaternion order here is runtime **wxyz**:

- Input Q: `(0.8183324, 0.20895459, 0.51877105, -0.13246402)`.
- HMD yaw: `0.61` radians; expected post-Full Q: `(0.95384705, 0, 0.3002932, 0)`.
- Base consumed Q remained input Q; expected/actual dot `0.93634737`, below test threshold `0.99999`.
- `red-elevated.log`, `red.xml`: one test, one behavioral failure, exit 1. Exact pinned protocol verification passed. The prior restricted attempt `red.log` failed to connect to Gradle's local daemon and was an environment failure, not behavioral proof.

The same public-input test passed after the two-file patch (`green-initial.log`). Subsequent tests preserve admitted Q unchanged and verify the returned `Tracker.getRotation()` value recorded at real FK reads.

## Scoped T1–T10 results

These are software behavioral results, not physical alignment or SteamVR observations.

| ID | Result | Evidence/observable condition |
| --- | --- | --- |
| T1 normal baseline | PASS | Existing `SkeletonResetTests` 3, `MountingResetTests` 65 and normal-Tracker comparator; Full/Yaw/Mount retained. |
| T2 Q-only Full | PASS | Public encoded Q with explicit MVIP Q and null 6DoF selection; real Full Reset yields analytical HMD yaw and ordinary Tracker parity. |
| T3 actual boundary | PASS | Real `ConsumedRotationInput` from FK reads is P=false/Q=true with absent P scalars and corrected Q; admitted Q unchanged. |
| T4 skeleton/computed | PASS | Real target bone plus computed tracker orientation parity; desktop same-frame JSON numerically verifies CHEST bone/computed and consumed Q in detailed/reset-window modes. |
| T5 P isolation | PASS | After Full Reset, large P-only changes with fixed Q leave consumed Q and all computed poses unchanged; previous CHEST/leg position-isolation tests also pass. |
| T6 Q response | PASS | Noncommuting post-reset motion changes actual consumed Q and preserves ordinary Tracker bone/computed parity. |
| T7 repeated Full | PASS | Second Full Reset from a changed current Q returns to expected head-yaw reference without double offsets. |
| T8 Yaw/Mount | PASS | CHEST, LEFT_UPPER_LEG and LEFT_UPPER_ARM; configured 0.2s yaw interpolation advances/completes, nondegenerate tilted Mount changes mountRotFix by >0.05 rad, matches ordinary handler, then noncommuting post-Mount motion matches actual Q/bone/computed. Private saveMountingReset remains false even when consumer setting is true. |
| T9 lifecycle | PASS | Exact Tracker/handler survives same-binding generation/topology refresh and public session reconnect. New Q identity, unassign/reassign, output-mode change and IK opt-out clear old state. Duplicate/rollback commits do not advance private yaw interpolation. |
| T10 loss/recovery | PASS | Valid -> lost -> recovered and stale -> reconnect retain the unchanged binding's reference. Unavailable input has no FK consumption/active mask; Full Reset while absent does not modify its dormant handler epoch. |

Negative controls also pass: already-adjusted Slime input, implicit legacy Main and 6DoF rotation fallback remain no-reset private inputs; feedback exclusion remains object-based. FULL-pose real IK, calibrated topology, and component-continuity regressions passed.

Independent review requested stronger T8 geometry because the initial upright mounting fixture could pass without Mount being effective. The final tests above include the tilted pose, a nonzero mount transform, and post-Mount motion. Production code did not change for this review correction.

## Executed command groups and logs

All Gradle calls ran from the repository root with JDK 17 found through PATH, `GRADLE_USER_HOME=<repo>/Build/Dependencies/gradle`, and `GRADLE_RO_DEP_CACHE=<user profile>/.gradle/caches`. Invocation prefix: `gradlew.bat --offline --no-daemon --max-workers=2`. No dependencies were downloaded.

Logs and preserved XML live under `Build/diagnostics/full-reset-fix/`.

| Log | Tasks/filter scope | Result |
| --- | --- | --- |
| `red-elevated.log` / `red.xml` | `:server:core:test --tests dev.monaka.tracking.MvipQOnlyResetTests`, original one-test fixture, base production | expected behavioral FAIL, 1/1 |
| `green-initial.log` | same original single case, fixed production | PASS |
| `green-lifecycle.log` | expanded Q-only class | 4 PASS / 1 fixture-construction FAIL; rotation_only was incorrectly marked tracked and codec rejected it; corrected to degraded |
| `green-q-only-desktop.log` | expanded Q-only class plus desktop composition | 6 PASS / 1 fixture-contract FAIL before desktop tests; CHEST has no Direct role, so lifecycle output-mode test was moved to supported LEFT_UPPER_LEG |
| `green-q-only-desktop-final.log` | Q-only class 7 plus complete `SolverCompositionIntegrationTests` 21 | PASS; before final T8 assertion strengthening |
| `accepted-regression.log` | desktop 6 classes + core 14 classes + `:server:desktop:shadowJar` | shadowJar completed; desktop 42 PASS / 1 FAIL; core compilation completed but core test task did not run before desktop task failure |
| `core-regression.log` / `core-regression-results/` | final core 14 classes including strengthened T8 | **153 PASS, 0 failure/error/skip**, exit 0 |
| `baseline-desktop.log` / `baseline-HybridServerIntegrationTests.xml` | one unchanged failing desktop method, only two production files temporarily at exact base via git show | PASS; original edited bytes restored in finally, recorded in `baseline-restore.log` |
| `current-focused-hybrid.log` / `current-HybridServerIntegrationTests.xml` | same single desktop method, restored fixed sources, plus shadowJar | FAIL at different assertion (line 140); shadowJar up-to-date; not accepted as a pass |
| `hybrid-diagnostic.log` / `hybrid-diagnostic-probe.log` / `hybrid-diagnostic.xml` | exactly one read-only diagnostic variant of that method; assertions, clocks and production unchanged | instrumented case PASS; original test bytes restored in finally; this does not supersede the two uninstrumented failures |

Core class counts: ConstraintPipeline 4; ConstraintResolver 6; HybridFoundation 3; MonakaRuntime 17; MonakaSolverComposition 22; MtpRotationOnlyDownstream 1; MvipQOnlyReset 7; MvipSolverBoundary 3; OwnedFeedbackBoundary 7; PQComponentContinuity 10; FullResetDiagnostics 4; MountingReset 65; PositionalIKMultiConstraint 1; SkeletonReset 3.

Desktop class counts from the broad invocation: SolverCompositionIntegration 21/0 fail; SameFrameDiagnostics 8/0; MonakaDesktop 6/0; NormalBodyGuidance 2/0; NormalPositionRuntime 2/0; HybridServerIntegration 4/1. No skipped cases. Preserved broad XML is `desktop-regression-results/`.

## Unresolved Hybrid regression — separate from scoped Reset results

`HybridServerIntegrationTests.optedInCorrectionLearnsDuringDirectAndAppliesOnceToFallbackIkInput` failed in the broad run at line 170 (`hipTracker!!`, after send 8); its single-method base-production comparison passed. A single-method current-production comparison then failed at line 140 (correction ready after send 2). Do not erase the failures or label them proven pre-existing. The one authorized read-only diagnostic variant passed; no retry-until-green policy was used, and it is not counted as an uninstrumented regression pass.

Static facts: this fixture uses `assignments.configure`, so `sourceSelection=null` and the new reset-aware map is empty. Its fake clock advances 10ms per send, whereas `Tracker.setRotation` records System.nanoTime. Unchanged `SlimeIndependentImuOrientationCapture` maps sample time by subtracting real receipt age from fake observation time. Unchanged `AssignedImuSampleFreshness` rejects newer sequences whose mapped sample time is not strictly newer, and the fixture uses a 100ms legacy IMU age limit.

The diagnostic observed send 1 fakeNow=1010ms / sampleAt=984.0627ms / IMU age=25.9373ms / learningReason=first_pair / ready=false; send 2 fakeNow=1020ms / sampleAt=1016.963ms / age=3.037ms / learningReason=accepted / ready=true. Thus real receipt latency varies by more than the virtual 10ms step. Send 8 was healthy in this diagnostic run; subsequent deliberately stale heartbeats correctly reported imu_sample_stale. These observations support timing susceptibility, **not the cause of the two particular uninstrumented failures**, whose age/rollback evidence was not captured. Residual Hybrid regression remains unresolved; do not claim a clean broad regression group. Original temporary instrumented test bytes were restored exactly (SHA256 `f1c686bdeb6a86531f5212aacbd55b16c94a11613ce84dc64cac39fee093dd2d`). No unrelated production or permanent fixture fix has been made.

## GUI, dependencies and review boundaries

- GUI reset request: **STATIC_REVIEW**, unchanged. Independent Sol `normal_reset_audit` verified `ScaledProportions` -> ResetButton -> hooks/reset -> RPCResetHandler -> VRServer Full entry. No existing dedicated request test was found; GUI request dynamic verification **NOT_RUN**, with no invented mirror test.
- Protocol verification: PASS exact artifact/manifest/lock/contracts/content tree during builds. Active kit source/schema `ea1e02c15c20693070ce69cfbc54941ee7ae8e7d`; no protocol changes.
- Protocol lock SHA256 `e196692dbe3c533fdd93e18e73c24a660cbefc88780802b10119c1dfc3398e55`; codec JAR SHA256 `2ba38736b6a2476e78b274e71006c14e438987ed298d81171167a867d6c87bc9`.
- Fixed production content SHA256: ConstraintIkWriteback `c4edbafd50de9362d174995ef0fd4f0f0c25a1527729f26cf4a578dd606ea028`; MonakaSolverComposition `058c1271a408470afc9ef75764ec14eaadee905340f10dc4b4ffd722499bb3a2`.
- Private time advances on **preflight-accepted/reserved** composition ticks. Duplicate/sequence-rollback rejection does not advance it; the dormant future/test-only position owner can reserve before a later NOT_CONFIGURED rejection, as before. No additional behavior was added for that unused path.
- Independent review: ACCEPT after T8 rework; parent G2: **scoped Full Reset ACCEPT**. Scoped T1–T10: SOFTWARE_PASS. Overall selected regression group: PARTIAL (Hybrid FAIL unresolved). PHYSICAL_HIL: HIL_WAIT / NOT_RUN. CUTOVER: NOT_RUN.
- Candidate decision: permitted for the Full Reset-only HIL once the authorized local topic commit and normal `Build_Runtime.cmd --offline` succeed with matching provenance. The supplied instruction sections 12/15 do not require all CI green; this does not turn the unresolved Hybrid failures into passes or established pre-existing debt.
- At document commit time, normal build/provenance are still to run. Final actual status, commit/runtime/kit hashes, build logs and clean-status constraints belong to `Build/diagnostics/full-reset-fix/final-candidate.json` and the standard `Build/provenance/windows.json`. Rollback base is the recorded base rebuilt through the normal command; no extra dated binary copies.
