# MonakaVR formal integration candidate — 2026-10-21 JST

Status: **DRAFT / VALIDATION IN PROGRESS / DO NOT MERGE**.

This candidate preserves the original histories of PRs #8, #12, #11, #5, #6, #7 and #4, in that order, using seven explicit merge commits on `integration/monaka-formal-candidate-20261021`. The base is fixed `dev` at `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`. The passing reference is PR #13 at `da596a6bee1e1a6e74bf2a9e9d58d504c84b161f`. Neither verification PR #9 nor #13 is an ancestor or a merge input. `dev` and `main` have not been modified.

## Inputs, history and tree comparison

The [machine-readable ledger](formal-integration-20261021.json) records current source PR HEADs, the seven integration merge commits, file hashes, source ownership, reference CI jobs and candidate validation status. Each original PR HEAD is retained as the second parent of its corresponding merge commit and is an ancestor of the candidate. Source PRs remain open Drafts, without closure, retargeting, squash, rebase or force push.

At assembly commit `4c8a026f2b657d47027e5bb8b86e9335a87c6d0a`, all **215** input paths are byte-for-byte identical as Git blobs to PR #13. This includes all product code, tests, localization, fixed protocol artifacts and inherited documentation. There are no additional API, protocol, Fusion/IK or Bridge-boundary edits.

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

Local JDK17 Spotless, Core/Desktop, MTP process E2E and GUI/component checks: **PENDING**. Candidate GitHub Actions for Android, Windows/Linux/macOS packages and Monaka v2 software evidence: **PENDING**. Reference PR #13 results are reference evidence only and are not substituted for candidate runs. Final counts, URLs and tested source identity will be appended when verified.

Any unexpected product difference, API change, unresolved conflict or new test failure stops integration work for diagnosis. No CI or Spotless skip/suppression/threshold change is permitted.

## Merge decision and retained limitations

An independent review and explicit user approval are required before a formal merge. This task will not merge to `dev` or `main`, or merge verification PR #9/#13. A reviewer should check the seven retained PR histories, four collision files, exact reference equality, final local checks and candidate Actions results before approving a merge strategy. Do not merge the constituent PRs again blindly after a candidate merge; their commits are already ancestors, and their open PR disposition must be reconciled by maintainers after approval.

Real hardware HIL, HMD, physical SteamVR/driver transport and actual JNA DLL binding: **NOT RUN**. The archived PICO package remains excluded from active compilation. Position Correction remains production OFF; the pure body rotation guidance is not activated. The standalone diagnostics/mode components retain their original product-screen integration limitations. Software checks do not certify those pending hardware/product gates.
