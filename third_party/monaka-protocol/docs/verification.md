# Verification record

Environment: Windows 11 build 26200 x64, MSVC 19.51.36257.0, CMake
4.3.1-msvc1, Microsoft OpenJDK 17.0.20.1+1-LTS, Kotlin 2.3.10,
Gradle 8.14.4, Python 3.13.15. Dependency file hashes are locked.

| Check | Command | Result |
|---|---|---|
| C++17 Windows configure/build | `cmake -S cpp -B build/cpp -G "Visual Studio 18 2026" -A x64 -DMONAKA_BUILD_TESTS=ON` then `cmake --build build/cpp --config Release` | PASS |
| C++ model checks | `ctest --test-dir build/cpp -C Release --output-on-failure` | PASS, 1 test |
| JVM17 build | `gradle -p jvm --offline --no-daemon build` using installed Gradle 8.14.4 | PASS |
| Schema/fixtures/cross-language | `python tools/test.py` | 74 fixture cases, 44 cross-language directions PASS |
| Contract and boundaries | `python tools/check_boundaries.py` | PASS |
| Performance | `python tools/benchmark.py` | Measured; see `performance.json` |
| Linux C++17 build | CI definition supplied in `.github/workflows/ci.yml` | NOT RUN locally: no Linux/WSL available |
| Hardware | Not required by Task 1 | NOT RUN; no accuracy or connection claim |

The model tests cover invalid in-memory nonfinite numbers, negative U63,
unpaired UTF-16, unchanged C++ output on failure, and future minor preservation.
Fixture tests use an independent Draft 2020-12 validator, then both codecs.
They cover all twelve public error codes, boundary integers, byte lengths,
depth/size limits, duplicate keys (including escaped keys), strict JSON,
partial components, q/-q, unknown battery, derivatives, confidence and
capabilities. Cross tests compare decoded/re-encoded semantic JSON, with the
specified encoder minor=0 normalization. The future-minor decode is also
checked directly to retain minor=65535.

Consumer sequence/session/loss scenarios are declarative fixtures. No runtime
freshness cache, replay manager or clock synchronization is implemented or
claimed to have been validated here.

Windows sandbox attempts initially failed to initialize MSVC/Gradle and read
pip-installed validation dependencies. The same operations succeeded outside
the sandbox. The first fixture run found a JVM console encoding issue in the
test runner; explicitly selecting UTF-8 fixed it. The wire codec already uses
strict UTF-8. Gradle reports a Gradle 9 compatibility deprecation; the build
uses the pinned Gradle 8.14.4.

Distribution verification is run after the clean source commit, using
`python tools/package.py` and `python tools/verify_kit.py --cmake <cmake>`.
Its actual result and final artifact hashes are reported in the external
handoff report, avoiding a circular dependency on the ZIP's own hash. The
verifier checks every included file hash and compiles/executes isolated C++
and Java 17 consumers of all four messages using only the extracted kit.

C1 remains a candidate. Master reconciliation and Linux execution remain
separate outstanding validations; neither is represented as a PASS.
