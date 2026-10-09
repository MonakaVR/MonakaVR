# Current Codex entry point — Architecture Revision

Status: active coordinated revision as of 2026-09-16.

This file exists to remove ambiguity between the historical Task 1–5 instructions and the newer modality-aware Architecture Revision.

## Normative precedence

For any new or resumed Codex work on `refactor/monaka-layer-separation`, use this order:

1. The user's latest explicit instruction for the current run.
2. `docs/architecture-revision.md` — active Architecture Revision and cross-repository responsibility model.
3. `docs/C2.md` — active wire 2.0 contract for this revision.
4. The applicable source code, tests, schemas, fixtures, and generated v2 artifacts at the checked-out commit.
5. `docs/C1.md` and `docs/codex/Task1_MonakaProtocol.md` only as historical v1 / original Task 1 evidence.

If an older document conflicts with C2 or the Architecture Revision, do not preserve the older behavior merely because it appears in the historical task text. Report the conflict if it affects compatibility or acceptance evidence.

## Current checkpoint

At commit `572e58cfa20b8b4335207ea5dbcb3f04c587ddff` the repository contains the v2 source model, schema, codecs, fixtures and v2-oriented validation/package tooling. The four downstream repositories have not yet been migrated to v2 at the time this checkpoint was written.

Before handing v2 to downstream repositories, finish and record the Protocol freeze gate:

- run the dedicated v2 cross-language validation (`tools/test_v2.py`), not only the legacy v1 test path;
- package wire major 2 explicitly (`tools/package.py --wire-major 2`);
- verify the generated v2 kit/manifest/lock and pin their hashes;
- ensure CI actually executes the v2 validation/package/verification path;
- keep hardware status as NOT RUN and do not treat v1 artifacts as v2 acceptance evidence.

Do not infer modality from legacy numeric fields and do not generate a synthetic IMU stream from a FULL sample.

## Resume rule

A fresh Codex session should first verify the branch HEAD and read this file, `docs/architecture-revision.md`, and `docs/C2.md` before making changes. It should not restart from the old Task 1 bootstrap plan or downgrade the runtime contract to C1/v1.
