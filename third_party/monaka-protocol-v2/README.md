# MonakaProtocol

C1 candidate wire 1.0: typed models, JSON Schema 2020-12, strict codecs,
validation and fixtures. Reference master reconciliation remains pending.
Read [C1](docs/C1.md) before implementing a consumer.

## Build and verify

Prerequisites: C++17 compiler, CMake 3.20+, Java 17, Gradle 8.14.4, Python 3.13.
The tested Windows compiler is MSVC 19.51.36257. Kotlin is pinned to 2.3.10.
Gson 2.11.0 and Kotlin runtime 2.3.10 JARs are vendored; the Kotlin Gradle plugin
is fetched by Gradle when not already cached. No Maven publication is assumed.

```sh
cmake -S cpp -B build/cpp -DMONAKA_BUILD_TESTS=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build/cpp --config Release
ctest --test-dir build/cpp -C Release --output-on-failure
gradle -p jvm --no-daemon build
python -m pip install -r tools/test-requirements.txt
python tools/check_boundaries.py
python tools/test.py
```

`gradle build` compiles the JVM fixture runner. The actual model/fixture and
cross-language assertions run through `tools/test.py`, not Gradle's empty
JUnit task. Both Windows and Linux configurations are in CI; consult the
verification report for which were actually executed.

## Use the kit in one repository

Extract `monaka-protocol-kit-v1.0.zip` into `third_party/monaka-protocol`.
Verify its external handoff manifest and internal `SHA256SUMS` first.
Copy the supplied `protocol.lock.json` to your dependency lock location.

CMake consumer:

```cmake
add_subdirectory(third_party/monaka-protocol/cpp)
target_link_libraries(your_target PRIVATE MonakaProtocol::Codec)
```

Include `monaka/protocol/v1/codec.hpp`; use
`monaka::protocol::v1::DecodeEnvelope` and `EncodeEnvelope`. `Envelope` is a
variant of four independent message structs. Error names are available through
`ErrorCodeName(error.code)`. Decode and encode outputs remain unchanged on
failure. A success clears the error message; the error code is meaningful only
on failure. U63 values are signed 64-bit nonnegative integers in the model and
decimal strings on the wire. Nullable samples are `std::optional`.

JVM consumer: add **all three** JARs from `jvm/libs` to the runtime classpath,
or use local file dependencies in your build. The API package is
`dev.monaka.protocol.v1`. `MonakaCodec.decodeEnvelope(ByteArray)` returns
`DecodeResult.Success(Envelope)` or `Failure(ErrorCode, message)`;
`encodeEnvelope(Envelope)` returns `EncodeResult.Success(ByteArray)` or
`Failure(ErrorCode, message)`. Kotlin nullable samples use `null`.
See `jvm/runtime-dependencies.json` for the exact runtime list. The kit's JVM
sources can also be rebuilt with its Gradle files.

Unknown optional fields are accepted and discarded. Optional derivative
absence decodes as null; encoders emit explicit null and wire minor 0. No
identity fallback exists for an unknown MTP coordinate convention. Consumers
must implement session/sequence/freshness policy; this library has no cache,
clock synchronization, sockets, device access or runtime lifecycle.

POTB v1 and PICO C ABI v1 remain different protocols. Equal version numbers
do not imply compatibility. Existing consumers require explicit adapter
migration in their owning repositories; this repository changes none of them.

## Packaging

After committing a clean source tree and running the checks:

```sh
python tools/package.py
python tools/verify_kit.py
```

Use `--cmake /path/to/cmake` for the verifier when CMake is not on PATH.
The lock records real source/schema commits and hashes of schemas, fixtures,
API sources and binary artifacts. ZIP hash lives only in the external handoff
manifest. `dist/` and build products are excluded from the source commit.
