# Issue #10 Kotlin Spotless baseline cleanup

This is a partial formatting-only repair for [Issue #10](https://github.com/MonakaVR/MonakaVR/issues/10), based on clean `dev` commit `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589` on 2026-10-21 (JST). Work branch: `chore/issue-10-kotlin-spotless`. Keep the PR in Draft: the complete Spotless gate still fails for the reasons below. No merge to `dev` was performed.

## Exact baseline and changed files

The clean JDK17 `:server:spotlessKotlinCheck` reproduces the first reported file, `AssignedImuSampleFreshness.kt`, and **77 other formatting files**. Inspecting Spotless's separate lint artifacts also finds **69 additional, disjoint files** with non-autocorrectable errors. The issue's 78-file formatter summary does not include these files.

| Baseline category | Main sources | Test sources | Resolution |
| --- | ---: | ---: | --- |
| Autocorrectable formatting | 64 | 14 | All 78 corrected and reviewed |
| Wildcard imports | 11 | 57 | All 68 corrected; 127 wildcard imports expanded |
| External C ABI function naming | 1 | 0 | Unchanged; still fails the existing rule |

The result changes **146 Kotlin files**. [inventory.json](inventory.json) is the complete path list for all 147 baseline files, including the unchanged ABI file. Each entry records its category, rule, original Git blob, original/final SHA256 and byte count, actual removed/added imports, and non-import token changes. `baseline_git_sha256` and `final_git_sha256` hash Git-normalized LF source bytes; `baseline_sha256` separately hashes the original Windows checkout bytes. This distinction avoids attributing CRLF checkout conversion to a source edit.

Formatting covers indentation, line wrapping, trailing commas, semicolon separation, required block braces, modifier/import ordering, and ktlint's block-to-expression-body conversions. Every non-import token difference is formatting punctuation, `return`/`=` for an equivalent expression body, or the explicit `: Unit` preserving the existing throwing `pump()` override's return type in an archived PICO test. No literal, identifier, arithmetic/logical operator, or declaration name was changed outside imports. The two archived PICO sources remain excluded from the active build as before; their small source diffs were reviewed directly.

## Explicit import analysis

Imported names were selected from actual Kotlin declarations, the unchanged hash-pinned protocol JARs, JDK17 classes, generated Protobuf member declarations, and the identifiers used in each file. Nested Protobuf types are not treated as outer-class members. Existing JUnit `Test` imports and aliases such as `LocalStatus`/`WireTrackerStatus` retain their original bindings. Unused candidates were removed with the pinned formatter and compiler diagnostics; final full compilation verifies resolution. The inventory contains the final exact imports, rather than the overinclusive intermediate candidate lists.

No blanket expansion of existing allowlisted wildcard imports elsewhere in the repository was attempted. The 127 replacements are confined to the 68 files already reported for manual import debt.

## Semantic and API review

The formatter's output was treated as a candidate, then checked against the baseline source and fresh JDK17 compilation. `spotlessApply` success was never assumed; its Kotlin task continues to fail on the ABI naming debt.

The 1,216 compiled Core/Desktop main/test classes have identical JVM field/method descriptors, access flags and generic signatures before and after the change. [NormalizeClasses.java](NormalizeClasses.java) documents the comparison used with cached ASM 9.8. Its normal comparison omits debug tables, source/coroutine line annotations and NOP instructions. Its `--api` mode additionally omits code, class annotations and constant values, so the API result is a signature comparison, not a claim that every annotation or constant is identical.

After normal comparison, 1,208/1,216 class hashes match. The eight remaining differences were reviewed:

- `BuildConfigKt` and `Main` reflect the expected clean-baseline versus dirty-worktree build metadata (`GIT_CLEAN` and the displayed `-dirty` version suffix).
- Six test-only classes differ in temporary-variable/value-class lowering, equivalent stack operations, try/finally jump layout, or compiler metadata. Their source tokens and disassembly were inspected, and their existing suites pass. The full list is in [validation.json](validation.json).

All production Core classes match after normalization. All production Desktop classes other than those two build-metadata classes match. This includes the Fusion/IK paths. Public API signatures, algorithm operations, protocol/generated sources, dependency pins, build configuration and CI gates are preserved. No suppression, target exclusion, threshold change or skip condition was added.

To reproduce the class comparison, keep separate clean baseline/final `server/{core,desktop}/build/classes` directories, compile the supplied Java helper with JDK17 and ASM 9.8 on the classpath, and run it for each directory:

```text
javac -cp <asm-9.8.jar> NormalizeClasses.java
java -cp <helper-directory-and-asm-classpath> NormalizeClasses <classes-directory> <hash-manifest>
java -cp <helper-directory-and-asm-classpath> NormalizeClasses <classes-directory> <api-manifest> --api
```

These are comparison artifacts, not runnable replacement class files. A fresh compilation is required: an intermediate incremental compile produced different multi-field value-class lowering, so the recorded final comparison uses the clean build.

## Validation

Host: Windows x64; Microsoft OpenJDK `17.0.20.1+1-LTS`; Node `22.17.0`; pnpm `10.33.0`. The final tested source manifest SHA256 is `50c04370482c9012163cd8df0a088b4657240714d276c055a03570b3da0a15ca`. It hashes the inventory's ordered `path + space + final_git_sha256 + LF` records. The final five checkout line-ending repairs were checked to preserve those exact Git-normalized source bytes.

| Check | Actual result |
| --- | --- |
| Clean baseline Core/Desktop tests and shadow JAR | PASS |
| Final clean Core tests | PASS: 1,940 tests, 0 failures/errors/skips |
| Final clean Desktop tests | PASS: 153 tests, 0 failures/errors/skips |
| Final shadow JAR | PASS |
| MTP process E2E | PASS: all 11 stages |
| Fixed protocol kit verification during compilation | PASS |
| GUI lint (TypeScript, ESLint, Prettier) | PASS |
| GUI production build | PASS |
| Complete `spotlessCheck --continue` | FAIL: existing Java comment wrapping and unchanged external ABI function naming |
| Kotlin formatting/import debt repaired here | All 78 formatting and 68 wildcard-import files resolved; ABI naming remains |
| Hardware, HMD, SteamVR runtime/driver transport | NOT RUN |

Commands used:

```text
gradlew.bat :server:spotlessKotlinCheck --no-daemon --console=plain
gradlew.bat :server:core:clean :server:desktop:clean :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --no-daemon --console=plain
gradlew.bat spotlessCheck --continue --no-daemon --console=plain
pnpm install --frozen-lockfile
pnpm --dir gui run lint
pnpm --dir gui run build
```

`--continue` reports independent failures; it does not ignore them or convert the exit status to PASS. The pinned Kotlin formatter was run during candidate preparation; intermediate overinclusive imports failed parsing/compilation and were corrected before the recorded final clean run. The GUI install logged an optional `register-scheme` native build failure, but installation exited successfully and both requested GUI gates passed. [validation.json](validation.json) contains suite counts, E2E receipt, comparison results and hashes of the retained [logs](logs/).

## Remaining work and coordination

1. `PicoMotionTrackerBridgeNativeReceiver.kt:134`: ktlint reports `standard:function-naming` for the first `pico_ot_bridge_*` declaration. This JNA `Library` interface uses method names as native C symbols. Renaming methods or introducing a function mapper would change an external API/binding contract. The complete interface is byte-for-byte unchanged, with no suppression. Resolving this requires a separately reviewed ABI-compatible decision; this PR does not claim the naming rule passed.
2. `WindowsNamedPipeBridge.java`: two existing comment-wrap violations, already owned by [Draft PR #8](https://github.com/MonakaVR/MonakaVR/pull/8). That Java file is unchanged here. The full check intentionally remains red.
3. Coordinate overlapping files with concurrent feature PRs before integration. PRs #4/#5/#6 have no path overlap with this cleanup. PR #7 overlaps the baseline `MonakaConfiguration.kt` and `MonakaInputConfigurationApi.kt`; PR #8 overlaps baseline `MonakaDesktopTests.kt` and `MtpHilCaptureTests.kt`. The hashes in the inventory prove this work began from the existing `dev` copies, without incorporating feature changes. These are overlap/merge considerations, not claims that the feature PRs introduced the baseline violations.

No feature branches were merged or rebased. No production hardware validation, release-evidence certification or CI PASS is claimed by this formatting repair.
