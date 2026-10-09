# MonakaProtocol

MonakaProtocol owns the shared wire contracts used between MonakaVR backends, MonakaBridge, and MonakaVR.

## Active contract on `refactor/monaka-layer-separation`

The coordinated Architecture Revision introduced wire 2.0 at commit `572e58cfa20b8b4335207ea5dbcb3f04c587ddff`.

Read in this order:

1. [`docs/architecture-revision.md`](docs/architecture-revision.md)
2. [`docs/C2.md`](docs/C2.md)
3. [`docs/C1.md`](docs/C1.md) only for rules C2 explicitly inherits unchanged

C1/wire 1.0 remains in-tree as historical compatibility and audit evidence. The current revision does **not** auto-upgrade v1 packets by guessing modality from numeric fields.

Wire 2.0 adds explicit `full` / `rotation_only` / `none` modality semantics and preserves backend identity separately from Bridge publisher identity. Main/Fallback body policy remains a MonakaVR responsibility, not a protocol responsibility.

## Build and verify

Prerequisites: C++17 compiler, CMake 3.20+, Java 17, Gradle 8.14.4, Python 3.13.
The tested Windows compiler for the original v1 work was MSVC 19.51.36257. Kotlin is pinned to 2.3.10. Gson 2.11.0 and Kotlin runtime 2.3.10 JARs are vendored; the Kotlin Gradle plugin is fetched by Gradle when not cached.

```sh
python -m pip install -r tools/test-requirements.txt
python tools/check_boundaries.py
cmake -S cpp -B build/cpp -DMONAKA_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/cpp --config Release
ctest --test-dir build/cpp -C Release --output-on-failure
gradle -p jvm --no-daemon build

# Historical wire 1.0 suite
python tools/test.py

# Current wire 2.0 capability/identity/time suite
python tools/test_v2.py
```

`tools/test_v2.py` executes strict v2 schema/codec cases and C++↔JVM cross-language checks. Hardware remains `NOT RUN`; protocol tests are software evidence only.

## Package and verify a fixed kit

Packaging requires a clean committed source tree and a built JVM JAR.

Historical v1 kit:

```sh
python tools/package.py --wire-major 1
python tools/verify_kit.py --wire-major 1
```

Current v2 kit:

```sh
python tools/test_v2.py
python tools/package.py --wire-major 2
python tools/verify_kit.py --wire-major 2
```

The v2 package is written under `dist/v2/`. Downstream repositories must pin the actual generated ZIP, manifest, source commit, and hashes. Do not infer or regenerate a supposedly identical handoff and then reuse an old hash.

`tools/verify_protocol_v2.py` is a downstream pin verifier/template: it expects a consumer repository to provide `dependencies/monaka-protocol-v2.lock.json`. It is not a replacement for this repository's `test_v2.py` + `package.py --wire-major 2` + `verify_kit.py --wire-major 2` source-tree validation flow.

## Consumer API

The repository intentionally retains both API namespaces:

- wire 1.0: `monaka::protocol::v1` / `dev.monaka.protocol.v1`
- wire 2.0: `monaka::protocol::v2` / `dev.monaka.protocol.v2`

A consumer must use one fixed major contract explicitly. v1 and v2 reject the other major at runtime; there is no implicit compatibility shim.

CMake consumers link `MonakaProtocol::Codec`. C++ includes the matching `monaka/protocol/vN/codec.hpp`; JVM consumers use the matching `dev.monaka.protocol.vN` package.

The protocol library owns models/codecs/validation only. Consumers own sockets, session/sequence caches, freshness policy, device access, mapping/calibration, body assignment, fallback, and runtime lifecycle according to the Architecture Revision.
# Wire 2.1 trusted HMD authority superset

See [C2.1](docs/C2.1.md) and [5Z projection audit](docs/authority-consumer-projection.md).
Existing observation/MTP messages remain wire 2.0. The ten authority/control/pose
messages use separate namespaces and canonical minor 1. Codec decode proves valid
wire only; no runtime transport or production trusted ingress is enabled.

Generate/check with `python tools/generate.py --wire-major 2 --wire-minor 1 --check`
(minor 0 also checks the unchanged v2.0 definitions in the superset library).
After C++/JVM builds, run `tools/prepare_old_v20.py`, `tools/test_v2.py` and
`tools/test_v21.py`. Package and isolated verification require explicit
`--wire-major 2 --wire-minor 1`; output is `dist/v2.1/monaka-protocol-kit-v2.1.zip`.
Windows clean-source release: `python scripts/release_v21.py --cmake PATH
--gradle PATH --java-home JDK17`; recheck with `--verify RELEASE_DIRECTORY`.
The historical v2.0 release profile keeps its strict minor 0 identity.
