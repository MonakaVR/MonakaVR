# Architecture revision — 2026-09-16

Normative input: user-provided “Generic Main Tracker + Orientation Fallback Source / Modality-aware Tracking”. This revision supersedes the older Task 1–5 component arbitration and fixed-v1-only instructions for this coordinated task. Historical C1 and delivered v1 artifacts remain historical evidence, not evidence for the new revision.

## Read-only gap analysis

All five clean local `refactor/monaka-layer-separation` branches matched the requested starting commits: Protocol `5f41586b51bd84bb1cea344879b5d6325fc2c47d`, PICO `c638f158516effd7fc6511daf179b9a927a099a6`, VIVE `eaaee63fcacb76d095ff743944fbff1f1427f015`, Bridge `acc329dce90dd6ba21387029cd54b6fc2d82fe8c`, VR `5ffaadf5f34323283a58a223a7fe339fc887a5ba`.

| Boundary | Current gap | Planned change |
|---|---|---|
| Protocol | No modality; MTP loses input source/evidence; future battery timestamp accepted | Strict wire 2.0, explicit modality and provenance, battery time validation, paired codecs/schema/fixtures |
| Backend | PICO sample sanity and VIVE loss quaternion sanity imply orientation usability | Fail closed on unverified loss semantics; publish explicit capability modality; retain physical identity |
| Bridge | Global logical tracker namespace; permanent peer collision; absent latch; all partial Direct poses out of range | Source-scoped output identity, bounded lease takeover, absent timestamp watermark, modality-preserving Direct/MTP |
| VR assignment | Many independent candidates per body; Slime implicit competition | One explicit Main and optional rotation fallback per target; unambiguous legacy migration |
| VR resolver | Per-component rank/priority selection | Main FULL owns both; otherwise no position, explicit fallback then usable Main rotation |
| IK | Proxy recreation and whole-chain rebuild lose offsets | Stable private identity; migrate calibration on necessary rebuilds without changing solver iterations |
| Isolation | One bad space calls removeSource | Remove only the affected logical tracker; publisher failures remain source scoped |

## Implementation order and compatibility

1. Protocol owns wire major 2 because required modality and identity semantics cannot be safely ignored by a v1 consumer. Keep the v1 codec and artifacts available; use separate v2 namespaces/schema. No automatic v1 pose-to-v2 capability inference in runtime.
2. Build and package an actual clean-source v2 kit; validate its manifest, internal hashes and actual JAR before importing it into the four consumers. Preserve the original v1 imports and upstream handoffs.
3. Update PICO/VIVE publishers, Bridge and VR; add regression tests at each boundary and real wire integration using Bridge output.
4. Build/test each changed repository and collect results from actual executions. No fixed PASS declarations. Hardware remains NOT RUN, cutover NO-GO, and redistribution grant F11 remains unresolved.

## Modality contract decisions

`full`, `rotation_only`, `none` describe current guaranteed capability, not sensor provenance. Full requires two valid components; rotation_only requires only valid orientation; none has neither. Invalid numeric diagnostic fields may remain, but cannot become constraints. Incomplete FULL measurements fail closed to NONE unless upstream independently guarantees usable rotation. No synthetic second IMU identity is created.

MTP source_id denotes the originating backend installation; a required publisher_id denotes the Bridge installation. Session/clock are the Bridge lifetime. Consumers key lifetimes and logical trackers by publisher/source and tracker. Thus same logical names on different sources survive, and independent Bridge lifetimes cannot silently collide. Input records retain backend source/device/session/sequence and orientation evidence.

Existing protocol v1 is preserved for historical tests only; the new runtime must reject it. Configuration migration never infers a fallback relation from quality/priority or connection order. Ambiguous assignments require explicit selection and fail validation.

## Audit evidence

Supplied audit ZIP SHA256: `c4571a1c3c51710cde11d1dc52bec404b336c4b3f086fd3ca1d14b5c68a44613`. Its reports/harnesses are evidence, not claims that local validation passed. F01–F12 remain tracked; regression success must precede closure of each applicable finding.
