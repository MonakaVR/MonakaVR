# P2R runtime + latest Desktop GUI integration for dev (2026-10-15)

User explicitly approved branch creation/merging and promotion of the complete existing Core lineage plus latest GUI candidate. No main change, force push, rebase, squash or cherry-pick.

- Previous remote dev: c11dc54ed1a745ffc4b15db7f820d8d0c1993990.
- Accepted runtime/P2R tip: ebdde9188b6d1a91befa22d499f3e4228d25a47b (212 commits beyond old dev, including the211 preexisting runtime commits).
- Latest GUI tip: 9da1981651d96cf5a9a0f730b477d0c8d4e84c60 (7 commits beyond old dev).
- Both tips are retained as ancestors in merge 0f5a0b659ec200a8dc0fd7b44780394492540bc2; product tree 0a64d7e5a589f66f88a1675ef19e042185e4e5ec. Automatic merge, no overlapping paths/conflict resolution. All GUI paths exactly match9da; all server paths exactly match acceptedc738.
- Dedicated branch: integration/core-gui-dev-v2c-p2r-20261015. Original Core and GUI checkouts stay untouched. Remote dev promotion occurs only after software checks below.

## Software verification on the merged candidate

- Bridge full CTest: 55/55; receiver prerequisite built and receiverTest passed.
- Core: 1924; Desktop: 153; failures/errors/skips: zero. Core MTP process E2E passed.
- Latest GUI service/controller/IPC: 26/26; real renderer/Electron/preload/IPC/tray fixture: 3/3. Product lint/typecheck/build passed.
- Trusted producer accepted host: 22/22. PICO accepted 4S: native13/C API1/replay5/Java lifecycle10; all passed, no hardware.
- MVIP P1: codec270/directions106/semantic44/API4; MBIP P1: boundary84/serialized76/negative mutants2; passed using recorded hash-verified P1 binaries (no protocol rebuild).
- Packaged executable replay, both ordinary and actual merged GUI-saved configuration: old three inputs all processed; old detector conflict2 -> runtime output conflict0; 405 normalization pairs each, zero non-capability mutations. Gate ON/OFF100 each; candidate remains invalid. GUI retains complete runtime_contract and all calibration/mapping/identity fields, changing policy/revision only.
- Protected 5S: 4285 hashes / 8376 inventory entries matched before/after.

Initial restricted-host Bridge CTest was 47/55: sandbox pipe/loopback/temp restrictions and missing fresh-clone JVM receiver prerequisites. Original logs retained. After building the receiver and running the complete suite on the configured host, all55 passed. Trusted producer's initial sandbox artifact-read failure was also preserved separately and successfully rerun. No product code was changed to suppress these failures.

## Known unresolved constraints

**Old CLI/tray writer lost-update risk remains UNFIXED: 2 of 8 forced schedules.** The measured reproduction used old Bridge5fbf2a9 CLI/tray SaveRoute, not a new P2R42ec6c8 race run. The Desktop lock is not a shared transaction/CAS boundary with those external writers. This integration does not add locking rules, fix the race, or assert all writers are safe.

Fixed Core fusion/recovery/server source remains identical to accepted c73805da1a64d0b161775decd60e5632ea295a0f. Accepted packaged Core JAR SHA256 remains ef37474a804ea0c3705fe556d21c97861fc4c1fd8feb1b622c42130c8f061863. Canonical P1 Normalizer is consumed unchanged; protocol/wire semantics unchanged; Admission Gate default OFF.

This is an authorized **dev software integration**, not release/safety approval or an updated HIL freeze. `hil_ready=false`. Full signed installer, unrestricted normal GUI+runtime startup, manual OS tray/file-picker, physical hardware, SteamVR/HIL/2C-4A: NOT RUN. Existing UDP processes are not stopped. Historical 4PF receipt2 integrity gaps remain recorded; no original evidence/package is rewritten.

FINAL PICO REACQUIRE THRESHOLDS: NOT YET VALIDATED

PICO same-tracker IMU authenticity: NOT VERIFIED / DEFERRED

## Evidence / publication

Full command receipts, JUnit/TAP/Playwright/process output, six-repository survey and source protection receipts are in workspace task-work/phase2cp2r-dev-promotion-20261015 (survey in task-work/repository-branch-survey-20261015). The external final publication report records final dev SHAs, remote equality and post-push CI. This file is committed after tests; it is the only new tracked difference from the verified product candidate.
