# Q-jump capture software acceptance

Software acceptance: **SOFTWARE_PASS**, limited to the diagnostic requirements below. Final source commits, clean-tree results, fixed runtime build provenance and candidate readiness are recorded after normal builds in `Build/diagnostics/qjump/final-candidate.json`. This committed software report does not substitute for that build receipt. Physical recurrence capture and cause determination are not claimed.

## Authorized scope and responsibilities

User explicitly approved the 2026-11-07 diagnostic task: three repository implementations, D1–D11, independent review, local topic commits, normal fixed runtime builds and necessary normal PC shutdown. Runtime restart, new physical HIL, push and dev integration are excluded. Three successful existing/new GPT-6.1 Sol / High agents act as sole implementation owners; no second-tier parallel spawn is claimed. This task-specific one-owner-per-repository layout follows the attached orchestration document and the available four concurrent slots including root.

Root fixed the design and reviews responsibility/acceptance. PICO owner `normal_reset_audit`, Bridge owner `qjump_bridge_owner`, Monaka owner `q_only_reset_audit`. Independent reviews: PICO owner reviewed Bridge/common C++ and the actual Monaka five-target artifacts; Bridge owner reviewed PICO-specific hooks and Monaka code/analyzer. This allocation kept the larger consumer implementation with its single owner. Reviewers did not edit another owner's code or rerun passing tests.

Initial clean bases and topic are in `QJUMP_CAPTURE_DESIGN.md`. All product settings and prior evidence are preserved. Monaka and Bridge were closed through normal Close/owner Exit, both confirmed exited. User confirmed PICO tray Exit. No application was restarted by this offline task.

## Acceptance matrix

| Gate | Required normal-path evidence | Result |
| --- | --- | --- |
| D1 | Smooth motion without angular events; real producer/Bridge wire and consumer computed-output OFF/ON parity, ordinary cadence preserved | PASS |
| D2 | q/-q equivalence at detection and analysis, original signs retained | PASS |
| D3 | 120-degree step captured with exact before/after and original identity/clock fields, including between rich retention slots | PASS |
| D4 | 180-degree step captured without nonfinite comparison or false sign sensitivity | PASS |
| D5 | Recovery retains pre/post evidence and clears the original persistent-offset candidate | PASS |
| D6 | Default >=2 s persistence; strict source high-water progress, short duplicate anchor retention without firing, 250 ms progress-gap/invalid/recovery/session clearance | PASS |
| D7 | Actual Full Reset observer fanout preserved; original correction epoch observed without changing reset calculations | PASS |
| D8 | Real assignment snapshot/relation recorded; generation/change trigger fixtures | PASS |
| D9 | Real object identity/active-retained/topology snapshot; lifecycle trigger fixtures and bounded diagnostic instance metadata | PASS |
| D10 | Session/clock/space lifecycle fixtures, exact >2^53 integer metadata, no fabricated protocol identity | PASS |
| D11 | Bounded five-target capture, no tracking-thread disk IO, worker-failure/overflow negatives, silent request and real-clock load below | PASS |
| Correlation | Actual-format exact native -> Observation -> MVIP -> consumed/bone links; valid strict rejection of asynchronous UTC windows | PASS within stated software scope |
| Analyzer | A–D exact connected candidates; E actual epoch-change correlation; same FK tick/site/owner; unrelated thinning gaps do not globally veto supported candidates | PASS |
| Independent review | Separate Sol inspection of current diffs/results/artifacts, followed by root specification review | ACCEPT |
| Candidate | Exact local topic commits, clean status and normal fixed runtime build results | See final candidate receipt |

Use each owner's focused evidence for layer-specific D cases; a lifecycle that does not exist at an upstream boundary is observed as a generic coordinated request, never fabricated there. Five selected bodies must survive the old diagnostic read-count truncation. Actual consumed data must come from FK reads, not a second getter or proxy storage surrogate. Full Reset and source loss/recovery behavior are not redesigned.

## Actual load and artifact evidence

The common C++ collector's actual wall-time run uses five entities × five lanes, 20 Hz accepted input, 20 Hz regular retention and 10 s pre/post. Its closed JSONL footer was read independently by root and the separate reviewer: complete/pre/post=true, no-new-post=false, dropped/errors=0, 25 real closing-boundary samples with original timestamps, and chronological/per-lane coverage. The file is 3,757,509 bytes; measured maximum ring is 1,841,090 bytes. Receipt: `MonakaBridge/build-qjump/qjump-nominal25-final-coverage/bridge-50ee6d99-3783-4d15-8adb-58d1fb2ef4bd/11e08ad8-a0f3-4c51-8dc2-858c9329e3ff.jsonl`. This tests the sampling/storage version `cc04a2e29881b6b0bc021550102e8e7f908761debf3c05b54eb6c7baa249dfde`. The final common source `c75546f75f7586b4e128aa28293c4d604a144407b4e48f32edcdc2de9aece86c` changes only T2 progress state; its nine-mode focused test and independent review cover that delta. It is not falsely described as another wall-time run at the later source hash.

The consumer's real product-path run uses five actual public-input fixtures through admission, composition and actual FK, without actor acknowledgement pacing. Requested scheduling is 1 ms; achieved throughput is **6,763 frames / 20.8793623 s = 323.908 Hz**, not a claimed measured 1,000 Hz. Artifact: `Build/diagnostics/qjump/five-target/monaka-29420f40-924c-41c7-9a6b-46f5454c3276/e880cd79-c715-4492-bf5c-74a5c60ba23a.jsonl`. File size 21,800,976 bytes; ring 10,108,952 bytes / 194 frames. Independent inspection confirmed complete/pre/post=true, drop/missed/error=0, all five bodies at admitted/resolved/solver/consumed/bone stages, original identity/lifecycle/read-site metadata, and all 4,466 computed rows resolving to the same actual tick's five solver targets/assignment generation. Existing Reset observer received both events in its bounded reset-window mode. The final T2-only high-water change has separate short impact tests; rich sampling/storage/queue limits are unchanged.

The earlier injected-clock 20.7 MB capacity result is retained as a separate software checkpoint, not presented as wall-time throughput. Initial queue-contention, capacity, sampling-tail, short-window overload and fixture failures remain in their owner logs. Their old PASS claims are not carried into final acceptance. The fixes are diagnostic-only: SPSC handoff for C++ service threads, actual boundary retention, compact consumer state, original-cadence lightweight detection with 20 Hz rich queueing, and accurate progress/completeness checks. No Q rejection, heading clamp, automatic Reset, calibration or IK change was introduced.

## Connected formats and analyzer

Actual PICO-sent Observation bytes were replayed through the real Bridge normalization/publication path, then the resulting unchanged MVIP bytes through the consumer codec/FK path. Final result: **37 exact connected observations / 18 unmatched observations**. All three current bundles have complete post-data and zero loss. `Build/diagnostics/qjump/connected-v4-performance-analysis/QJUMP_INCIDENT_ANALYSIS.json` correctly returns `INSUFFICIENT_EVIDENCE` for non-overlapping real UTC windows and event UTC mismatch; no timestamp or sequence was rewritten to create artificial simultaneity.

CASE A–E positive and negative classification is separately exercised by nine focused analyzer tests with synchronous software fixtures, including unchanged unrelated computed output, actual Reset epoch transitions, wrong identities/times, missing onset, separate read sites and same-frame references. These tests do not claim a simultaneous three-process physical incident. Actual C++ request bytes -> Kotlin silent capture are covered; the reverse-language receiving direction was not separately executed and is not reported as a software E2E PASS.

Detailed commands, exact paths, versioned checkpoints and failure/skip boundaries are in:

- PICO: `PicoMotionTrackerBridge/docs/diagnostics/qjump/QJUMP_PICO_EVIDENCE.md` and `SHARED_COLLECTOR_PROVENANCE.json`.
- Bridge: `MonakaBridge/docs/diagnostics/qjump/QJUMP_BRIDGE_IMPLEMENTATION.md`.
- Consumer: `docs/diagnostics/qjump/QJUMP_CONSUMER_IMPLEMENTATION.md`, `Build/diagnostics/qjump/light-rich-final-impact.log`, `perf-final-xml/`, `t2-high-water-recorder-final.log`, `t2-final-xml/` and `analyzer-case-a-final.log` under the same diagnostic output directory.

## Current limitations carried forward

- Monaka's exhausted status label is updated on the next trigger after the last permitted event; the event cap itself is enforced. The handoff therefore requires remaining event capacity as well as ARMED status. This display limitation does not change captured evidence or tracking.

- Earlier HIL demonstrated Full Reset and short five-point Q-only behavior; it did not capture the rare jump. Earlier 73-condition software probe did not reproduce persistent breakage within its scope. Neither is a cause determination.
- PC PICO capture observes native OutputA after HMD transport/PC acceptance, not inside the vendor SDK; missing packets and SDK-internal coordinate changes cannot be reconstructed.
- A layer candidate or nearby lifecycle change is not proof of causality or irreversible IK corruption.
- Long-term physical stability, a new real incident capture and interactive runtime behavior after this diagnostic build remain untested here.
- The previous selected desktop regression debt is not rerun or relabelled by this diagnostic task.

## Candidate, profile and rollback

Normal build entries are `python scripts/build_runtime.py` in PICO and Bridge, and `Build_Runtime.cmd --offline` in MonakaVR. Only these entries update each repository's fixed `Build/runtime`; no launcher redirect, APK change, install, registration or runtime start is part of this task. Each `Build/provenance/windows.json` records its exact committed source and payload. `Build/diagnostics/qjump/final-candidate.json` consolidates final source HEADs, owner-confirmed clean trees, build receipts and readiness without creating a self-referential Git commit hash in this file.

Use `NEXT_QJUMP_HIL.md` for the explicit opt-in variables, normal launch paths, at least 10 s warmup, local event directories, one-incident collection/analysis and normal stop procedures. Defaults are 20 Hz rich retention, 10 s pre/post, T1–T6 and 45-degree angular threshold; the supported byte/event caps remain 16 MiB ring, 32 MiB/event and 16 events/product/run. No old evidence is silently overwritten. Original rollback source commits are in `QJUMP_CAPTURE_DESIGN.md`; disable diagnostics by removing the enable variables for the next authorized normal launch, or rebuild an explicitly selected original source through the same normal entry if rollback is requested.

**SOFTWARE_PASS** for this focused diagnostic scope. **PHYSICAL_HIL=NOT_RUN; runtime restart/CUTOVER=NOT_RUN; push/dev integration=NOT_RUN.** `READY_FOR_QJUMP_CAPTURE` in the final candidate receipt means prepared for a separately authorized future capture, not that the rare physical fault has been reproduced or its cause established.
