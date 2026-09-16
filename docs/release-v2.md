# Current wire v2 software release (F10)

This is the current release-evidence entry point. Run it in a **clean committed
checkout of this repository**, not from a sibling repository. It never deploys,
registers a driver, opens hardware, rewrites history or changes a dependency pin.

Windows x64 prerequisites: Python 3, CMake/CTest and MSVC, JDK 17. Protocol also
needs Gradle 8.14.4 and the Python requirements in tools/test-requirements.txt.
MonakaVR uses its repository Gradle wrapper. Bridge requires the existing
verified OpenVR SDK checkout at build/openvr-sdk (or --openvr-sdk); CMake checks
its exact revision. No SDK or artifact is invented if it is missing.

    python scripts/release_v2.py --java-home "<JDK17>" --cmake "<cmake.exe>"
    # Protocol only: add --gradle "<gradle.bat>" if it is not on PATH.
    python -O scripts/release_v2.py --verify "<directory from latest.json>"

Each run creates a fresh native build under build/release-v2/<run-id>.
Gradle tasks rerun. Command lines, actual exit codes and logs, required JUnit/
CTest testcase names, artifacts and their SHA256 are recorded. Empty suites,
failures, errors, skipped required evidence, missing outputs, dirty source,
changed HEAD/bytes, wrong v2 kit/manifest/lock/C2 hashes and stale receipts fail.
The Python gates do not use assert or __debug__; their regression suite runs in
normal and optimized Python. Child historical Python tests run with
PYTHONOPTIMIZE=0 so their legacy assert checks cannot disappear.

Results: dist/release-v2/<source-commit>/<run-id>/:
- <repository>-software-v2.zip: tested runtime subset, protocol kit and evidence.
- handoff.json: actual archive hash, source commit and content-manifest hash.
- release.json: actual commands, test results, source tree and file hashes,
  dependency identity, artifact hashes and explicit unexecuted gates.
- dist/release-v2/latest.json: RUNNING / FAIL / PASS for the latest attempt.
  Never treat an older PASS as the result of a failed new run.

ZIP entries are sorted with fixed timestamps. Repacking the same evidence bytes
is deterministic; compiler output, absolute command paths and per-run receipts
are not promised to be bit-reproducible across toolchains or runs. Validation is
reproducible by checking out the recorded commit and rerunning the commands.
--verify checks the archive, nested kit, receipt and source against the current
clean HEAD without rebuilding. These are checksums/provenance, not signatures.

## Separation and identity

Consumer artifacts retain the **supplied fixed v2 kit** and its original source
commit. The consumer release HEAD is recorded separately. Protocol releases
produce a new kit at their own HEAD; that never silently repins another repo.
Current MTP identity is (publisher_id, source_id, tracker_id); lifetime scope is
(publisher_id, source_id). Actual frozen C2 codecs and fixtures test this path.
Historical C1 kits/Task2 extraction/Task3 and Task4 handoffs remain immutable
compatibility and provenance inputs. They are not current v2 release evidence.

Protocol tools/package.py and tools/verify_kit.py explicitly select --wire-major
1 or 2; their default 1 remains for historical callers. Use release_v2.py for a
current source-bound release. Historical static reporters (tools/report.py,
scripts/report_task2.py, package_handoff.py, verify_windows.py, verify_task5.py
where present) now fail with migration guidance rather than claiming a current
v2 PASS. Use the original historical commit to reproduce an old report.
verify_task5_upstream.py and historical import/extraction tools still verify
their actual old inputs; they do not certify a v2 release.

Repository-specific commands, mandatory test names and artifact paths are
reviewable in scripts/release_v2.json. release_v2.py, release_interop.py and
test_release_v2.py are identical tooling copies in the five repositories, not
new protocol/runtime dependencies. Each checkout works without sibling sources.
The workspace Quick/Full runners exercise the release-tool regression tests;
release generation remains an explicit, additional clean-tree gate.

## Scope and remaining gates

PASS means **Windows x64 software release evidence**, not hardware cutover,
installer certification or permission to redistribute vendor files.
PICO/VIVE hardware, axes/scale/orientation claims, physical Direct/MTP comparison,
SteamVR interactive, HMD, hardware cutover, Android deployment and GUI installer
are **NOT RUN**. The VIVE emitted samples are explicitly synthetic. Bridge's
Direct standalone, CTest and separate-process UDP tests are software tests.
MonakaVR verifies the actual app JAR's v2 codec with C++/JVM fixture interop,
active legacy dependency exclusion, tick ordering and required numerical IK,
fallback, identity, replay and feature-off regressions.

Historical license/provenance questions (F11) remain separate; this change does
not invent a license grant. F12 (Direct UDP port configurability) is untouched.
No runtime, protocol, solver, priority, coordinate or hardware semantics change.
