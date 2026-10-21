# MonakaVR formal integration candidate — 2026-10-21 JST

## Post-review update: current dev policy + public evidence hygiene

The initial 215-path source/reference identity reported below is a **historical statement about the assembly commit** `4c8a026f2b657d47027e5bb8b86e9335a87c6d0a` and the original candidate HEAD `3840a4cc3729cc60e7f33fe5f049d3596e2f8a11`. It must not be misread as current blob identity after the following evidence-only remediation.

- `dev` advanced independently by one documentation-only commit `9deecba91e58a2f01c4ec9f401b87389064bffd7` modifying `AGENTS.md`. That exact file was merged into this candidate at `c3c47d00a558505b6d0a857837ddad9455108584` (two-parent, non-forced). No production/test source changed during this merge; `dev` and `main` were not written by this work.
- PR #5 gained an evidence-only commit `636dab1f8a76bb2e5d0928269f9d0b087208d06b`: 11 published log files have user-specific home-directory names replaced with `REDACTED`, their Git-byte SHA-256 manifest is updated, and `docs/parallel/regression-matrix.md` documents the operation. This change was merged into the candidate at `6b69baad9b00f75c3eda0702e8c44b02ac0f81b1` using the PR #5 branch as a second parent.
- **Current reference correspondence before this addendum:** of the original 215 common PR #13 input files, **202 remain byte-for-byte identical**, while **13 evidence/document paths differ solely for anonymization and SHA provenance**. All product, test, translation, core solver, IO, and protocol files remain identical. PR #13's two temporary workflows and PR #13-specific ledger remain excluded. No CI policy is relaxed.
- **Git history:** previous publicly accessible Git commits still retain the original evidence logs. Updating a branch tip does not delete older commits. History rewriting or deleting previously published commits has **not** been performed or authorized; permanent erasure requires a separately controlled security/privacy process.
- **Producer/Bridge responsibility separation:** the user explicitly schedules this architecture work for a separate follow-on phase after the present software integration. The new `AGENTS.md` is incorporated verbatim, but its additional producer-absence, two-independent-producer, runtime boundary and product UI gates are **NOT RUN / NOT CERTIFIED** here. This is a scoped schedule separation, **not** a finding of full architecture compliance or a waiver of its constraints.
- Previous final-HEAD Actions 14 PASS / 0 FAIL / 1 expected SKIP apply to `3840a4cc...`. The current candidate after the two documentation/evidence merges requires fresh Actions results to revalidate that HEAD before any approved merge.


Status: **DRAFT / SOFTWARE VALIDATION PASS / INDEPENDENT REVIEW AND USER APPROVAL REQUIRED**.

This candidate preserves the original histories of PRs #8, #12, #11, #5, #6, #7 and #4, in that order, using seven explicit merge commits on `integration/monaka-formal-candidate-20261021`. The base is fixed `dev` at `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`. The passing reference is PR #13 at `da596a6bee1e1a6e74bf2a9e9d58d504c84b161f`. Neither verification PR #9 nor #13 is an ancestor or a merge input. `dev` and `main` have not been modified.

## Inputs, history and tree comparison

The [machine-readable ledger](formal-integration-20261021.json) records current source PR HEADs, the seven integration merge commits, file hashes, source ownership, reference CI jobs and candidate validation status. Each original PR HEAD is retained as the second parent of its corresponding merge commit and is an ancestor of the candidate. Source PRs remain open Drafts, without closure, retargeting, squash, rebase or force push.

At assembly commit `4c8a026f2b657d47027e5bb8b86e9335a87c6d0a`, all **215** input paths are byte-for-byte identical as Git blobs to PR #13. The whole-tree comparison also confirms equality of product code, tests, localization, fixed protocol artifacts and inherited documentation. There are no additional API, protocol, Fusion/IK or Bridge-boundary edits.

The complete tree differs from the reference only by the absence of:

- `.github/workflows/monaka-integration-spotless-stage.yml`
- `.github/workflows/monaka-parallel-component-validation.yml`
- `docs/parallel/pr13-integration-ledger-20261021.md`

These verification-only files were never merged into the candidate. This candidate adds its own two ledger files. Existing CI workflows, Spotless configuration, exclusions, thresholds and checks remain exactly at the fixed `dev` versions. Dedicated component checks are executed locally using the repository's existing test commands.

## Collision resolutions

| Merge | Managed path | Actual conflict | Resolution |
| --- | --- | --- | --- |
| #11 after #8 | `server/desktop/src/test/java/dev/monaka/tracking/desktop/MonakaDesktopTests.kt` | Auto-merged | Explicitly checked against PR #13; exact reference blob retained |
| #11 after #8 | `server/desktop/src/test/java/dev/monaka/tracking/desktop/MtpHilCaptureTests.kt` | Yes: portable JVM helper versus old `java.exe` expression | Exact PR #13 blob, retaining explicit imports, formatting and `testJvmExecutable()` |
| #7 after #11 | `server/core/src/main/java/dev/monaka/tracking/MonakaConfiguration.kt` | Yes | Exact PR #13 blob, retaining final mode save/active split, provenance/empty-file fixes and formatting |
| #7 after #11 | `server/core/src/main/java/dev/monaka/tracking/MonakaInputConfigurationApi.kt` | Yes | Exact PR #13 blob, retaining mode API behavior and explicit imports/formatting |

PR #8's current two overlap blobs and PR #7's current two overlap blobs were independently verified identical to the reference before resolving. No new implementation was invented during resolution. There is no remaining product/test difference from PR #13.

## Validation status

The candidate's local validation passed on Windows x64 with Microsoft OpenJDK `17.0.20.1+1-LTS`, Node `22.17.0` and pnpm `10.33.0`. Input blob manifest SHA256: `8b496a99919fadab856772d71a6346838e330931eba6274210642a618c5653d9`. Subsequent candidate changes edit only these two ledger files; all 215 input blobs remain exact matches to the validated reference.

| Local check | Actual result |
| --- | --- |
| Complete pinned `spotlessCheck` (Java/Kotlin/KotlinGradle/misc) | PASS |
| Core full suite | PASS: 2,015 tests, 0 failures/errors/skips |
| Desktop full suite | PASS: 153 tests, 0 failures/errors/skips |
| Desktop shadow JAR | PASS |
| MTP process E2E | PASS: all 11 stages |
| Fixed protocol artifact/hash verification during compilation | PASS |
| GUI TypeScript/ESLint/Prettier and production build | PASS |
| 02 diagnostics: dedicated TypeScript / Node / Edge | PASS: 40 Node tests, 17 Edge tests |
| 03 settings: dedicated TypeScript / Node / Edge | PASS: 10 Node tests including actual Core subprocess, 3 Edge tests |
| PR #5 Fluent localization regressions | PASS: 2 tests |

The initial external GUI runner passed its arguments as one string and failed before any test started. Its PowerShell argument splatting was corrected outside the repository; all eight actual GUI steps then passed without a product/test/configuration edit. Frozen dependency installation succeeded; the optional `register-scheme` native dependency emitted a build failure, so OS scheme registration is not certified. Requested lint/build and component tests passed.

Commands are the unchanged repository commands: `gradlew.bat spotlessCheck :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --no-daemon --console=plain`; `pnpm install --frozen-lockfile`; `pnpm --dir gui run lint`; `pnpm --dir gui run build`; the dedicated TypeScript/Node/Edge commands documented in the two fixture directories and used by PR #13's temporary workflow. The Fluent check compiles `gui/bridge-tests.tsconfig.json` and runs only the existing `parallel-localization.test.js` suite. The broader pre-existing native-validator-dependent GUI bridge suite is outside this requested dedicated check and was not claimed PASS.

Candidate GitHub Actions completed successfully on HEAD `e9a1c509bf72e674de9ae55b4c29ccf514ab5e84` (GitHub test merge `9fb73f89ec2f8bab8db14690b71c22f9c799d2f2`): **14 SUCCESS, 0 FAILURE, 1 expected tag-only SKIPPED** across the unchanged permanent workflows. These are candidate results, not the historical PR #13 results.

| Candidate Actions gate | Verified result |
| --- | --- |
| Java Checks / complete Spotless | PASS |
| Linux Desktop Server build/tests | PASS |
| GUI checks and assets | PASS |
| Windows/Linux bindings provider | PASS |
| Android APK and Gradle tests | PASS |
| Windows, Linux and macOS Desktop packages | PASS, all three OS jobs |
| Monaka current v2 software release evidence | PASS, clean-source build, receipt/archive verification and artifact upload |
| v2 release-tooling regressions, Windows/Linux | PASS, both OS jobs |
| Finalize Release Draft | SKIPPED, unchanged tag-only condition |

Full build: [run 39538072317](https://github.com/MonakaVR/MonakaVR/actions/runs/39538072317). Release-tooling regressions: [run 39538072205](https://github.com/MonakaVR/MonakaVR/actions/runs/39538072205). The `release-monaka-software-v2` artifact (ID `12317818826`) belongs to that exact candidate HEAD; GitHub archive digest is `sha256:874aed0727d1acd53d6a92e5740c2a0587582283e029dba0d75d94ec6f92991c`. This is software evidence, not hardware/JNA DLL/HIL approval.

The permanent-workflow job total differs from PR #13's reported aggregate because the two temporary reference-only workflows are intentionally absent. Their dedicated component checks were run locally as listed above; none of the permanent gates was skipped or relaxed. This final ledger update changes only its two documentation files. Any resulting documentation-only Actions rerun remains visible in [Draft PR #14](https://github.com/MonakaVR/MonakaVR/pull/14); its latest checks must also be reviewed before an approved merge.

Any unexpected product difference, API change, unresolved conflict or new test failure stops integration work for diagnosis. No CI or Spotless skip/suppression/threshold change is permitted.

## Merge decision and retained limitations

An independent review and explicit user approval are required before a formal merge. This task will not merge to `dev` or `main`, or merge verification PR #9/#13. A reviewer should check the seven retained PR histories, four collision files, exact reference equality, final local checks and candidate Actions results before approving a merge strategy. Do not merge the constituent PRs again blindly after a candidate merge; their commits are already ancestors, and their open PR disposition must be reconciled by maintainers after approval.

Real hardware HIL, HMD, physical SteamVR/driver transport and actual JNA DLL binding: **NOT RUN**. The archived PICO package remains excluded from active compilation. Position Correction remains production OFF; the pure body rotation guidance is not activated. The standalone diagnostics/mode components retain their original product-screen integration limitations. Software checks do not certify those pending hardware/product gates.
