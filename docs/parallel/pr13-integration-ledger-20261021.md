# Monaka parallel integration verification ledger — 2026-10-21

**DO NOT MERGE THIS BRANCH.** This record belongs exclusively to the synthetic integration Draft PR #13. The `dev` branch and seven original Draft PR heads are unchanged by this ledger.

## Integrated source references

- #11 `6d182b6a41ae1036698b3cb013af5ee9e6f57b3d`: formatting and explicit import baseline.
- #8 `d3039b8c3194e218c491fbc3a7f19483cbac21db`: Java comment wrapping and portable child-JVM test launcher.
- #7 `667d563df093ca60fd25212196dac801f4078eef`: offline mode persistence, safe saved/active split.
- #6 `7165c6d937f9e5c3ee3aac88324328de6985e30e`: dormant quaternion guidance.
- #5 `76f5b75e0210f2802aa235d313fa537c6a8d925d`: regression and Fluent en/ja.
- #4 `bebb9ab9195a7fe68d4b3483180214ec8d369c2b`: read-only source diagnostics.
- #12 `c5fbd69716d3effad0f5f87eff862c3cfa81c283`: archived JNA interface moved to Java without C-symbol renaming.

Built over `dev` `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`, with exactly four deliberate overlapping paths: two Core configuration paths use #7 behavior; two Desktop test paths use #11's explicit imports with #8's portable `testJvmExecutable()`.

## Verification-only pinned formatting

After that composition, GitHub Actions [`Monaka combined Spotless staging`](https://github.com/MonakaVR/MonakaVR/actions/runs/39522893735) **PASSED** `:server:spotlessKotlinApply`, `:server:spotlessJavaApply`, and complete unmodified `spotlessCheck` with no lint suppression or scope relaxation.

A bounded source-only formatting commit, `9456604dc388dc57fe148704ca4055d686027fa8`, changed seven paths:

1. `server/core/src/main/java/dev/monaka/tracking/MonakaConfiguration.kt`
2. `server/core/src/main/java/dev/monaka/tracking/MonakaInputConfigurationApi.kt`
3. `server/core/src/test/java/dev/monaka/tracking/ModeConfigurationProcessFixture.kt`
4. `server/core/src/test/java/dev/monaka/tracking/ModeConfigurationTests.kt`
5. `server/core/src/test/java/dev/monaka/tracking/ParallelRegressionTests.kt`
6. `server/desktop/src/main/java/dev/monaka/tracking/pico/desktop/PicoMotionTrackerBridgeNativeLibrary.java`
7. `server/desktop/src/main/java/dev/monaka/tracking/pico/desktop/PicoMotionTrackerBridgeNativeReceiver.kt`

The source PRs were not rewritten. These seven formatting changes must be carried into corresponding feature PRs or reproduced by pinned Spotless when integrating; do not merge this synthetic verification PR.

## Remaining validation gates

At this ledger commit, combined JDK17 Core/Desktop, Monaka release evidence, dedicated Node+Core subprocess+Edge GUI tests, Android and desktop packages require a fresh post-format full CI result. Do not reuse earlier PASS results from a different HEAD as proof for this source tree.

Hardware/HMD/SteamVR actual tracking, physical calibration, native PICO JNA DLL load, and Production Position Correction remain **NOT RUN / OFF**. Legacy PICO receiver is excluded from the current build's Java and Kotlin compilation in `server/desktop/build.gradle.kts`.

This ledger is an audit snapshot, not authorization to merge `dev` or `main`.
