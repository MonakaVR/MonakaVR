# Archived PICO receiver JNA interface: Spotless-compatible ABI names

Base: `dev` at `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`.
Scope: legacy PICO Receiver JNA source under `server/desktop/src/main/java/dev/monaka/tracking/pico/desktop/` only.

## Why Java
The original public Kotlin `PicoMotionTrackerBridgeNativeLibrary : Library` interface declares **18** methods named `pico_ot_bridge_*`. By default JNA maps the JVM method name to the exported native C symbol exactly. The pinned `ktlint(standard:function-naming)` rule rejects these snake-case names, but renaming them in Kotlin without a proven symbol mapper could break native calls.

An equivalent **public Java interface in the same package and with the exact same simple name** now declares the same 18 symbols and Java/JVM method descriptors. The Kotlin duplicate is removed along with its unused `Library` import. No native method call sites, structure field order, signatures, struct types, constants or native library selector are changed. No ktlint suppression, target exclusion or CI threshold modification is introduced.

## Explicit coverage limits
**This entire legacy PICO native package is currently excluded from BOTH Kotlin and Java Desktop source sets** by `server/desktop/build.gradle.kts`. It is an archived reference boundary, not a live production path. Thus a standard Core/Desktop build and test cannot establish that this JNA interface actually binds successfully to the native `pico_ot_bridge_capi` library.

The source-to-source audit compared all 18 original Kotlin declarations against their Java replacements for method name, primitive/Pointer/String parameter types, order and return type. Static audit: PASS. Real native DLL load, JNA reflective dispatch and hardware/SteamVR HIL: **NOT RUN**. In particular, Kotlin nullable/reference annotations differ from unannotated Java platform types; this does not change the JVM parameter/return descriptors but would require a separate active-build compatibility test before restoring this archived backend.

## Combined integration requirement
This PR removes the final Kotlin naming diagnostic identified by [Issue #10](https://github.com/MonakaVR/MonakaVR/issues/10). It must be checked together with:
- [PR #11](https://github.com/MonakaVR/MonakaVR/pull/11), which repairs the existing 78 Kotlin formatting and 68 explicit-import sources.
- [PR #8](https://github.com/MonakaVR/MonakaVR/pull/8), which fixes the separate Java comment wrapping and Linux process tests.
- [PR #4/#5/#6/#7](https://github.com/MonakaVR/MonakaVR/pulls), whose independently added Kotlin files must themselves satisfy the full current Spotless rules.

The combined `spotlessCheck` must PASS without ignoring the Java interface or changing lint configuration. Keep this PR Draft until the complete combined CI is green. Do not merge the verification-only integration PR.
