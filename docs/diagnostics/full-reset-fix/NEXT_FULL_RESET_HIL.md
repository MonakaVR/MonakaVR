# Next Full Reset HIL

OFFLINE handoff, 2026-11-06. **Do not launch HIL from this document automatically.** Current physical result is NOT_RUN. Independent review and parent G2 ACCEPT the scoped Full Reset fix while the selected regression group remains PARTIAL for the two unresolved Hybrid failures. Candidate readiness requires the subsequently authorized local topic commit and normal fixed-runtime build/provenance; actual status is recorded in `Build/diagnostics/full-reset-fix/final-candidate.json`.

Scope is one Full Reset confirmation plus small Q motion. No automatic R4/5-point FBT, RR-00F readiness work, producer configuration, pairing, process restart or other device operations are authorized by this handoff.

## Candidate identity

- Repository: MonakaVR; topic `fix/q-only-full-reset-20261106`.
- Rollback source: `85a9ead351abec6f56de37e1f854bbe96801ae33`.
- Candidate commit / fixed-runtime binary hash / normal-build result: see `Build/diagnostics/full-reset-fix/final-candidate.json` and standard `Build/provenance/windows.json` produced after document commit. The normal command is `Build_Runtime.cmd --offline`.
- Fixed normal runtime location: repository-owned `Build/runtime`, resolved by the ordinary launcher. No task-work/dynamic catalog/dated-package runtime dependency.
- Consumer reset state is in memory for the unchanged explicit body/Q binding; process restart requires an ordinary new user reset. No producer state or wire data is rewritten.

## One physical confirmation

1. When the user starts the approved fixed runtime through its normal entry, record commit/build identity. Do not build/install/register during normal launch.
2. Confirm CHEST: Q Source=MVIP, 6DoF Source=null, IK=true, useAsIkConstraint=true; current Q is valid. Actual FK diagnostic must show has_position=false / has_rotation=true.
3. Hold body/tracker still. Perform Full Reset once through the ordinary GUI control.
4. Check body-direction alignment and numeric actual-consumed Q, target bone and computed output across that reset. Admitted MVIP Q remains unmodified; diagnostic reset event alone is insufficient.
5. Move Q slightly and confirm continuous skeletal response, with no Position contribution.

PASS requires Full Reset reflected in the Q-only body, expected body-reference alignment, continued Q response and no Position contamination. It is a physical result only after these observations are recorded.

FAIL: capture the minimal before/after diagnostic, user-visible observation and reproduction condition; perform no in-HIL product fix. Return the discrepancy to a later offline task. A single failed check does not authorize producer or Bridge changes.

## Rollback and evidence

Rollback source is the base SHA above, using the normal repository build when separately authorized. Do not overwrite original logs or previous seals, create dated binary copies, or alter normal launch resolution. No push/dev/main merge/cutover is included.

Record candidate source/binary/kit hashes, Full Reset generation/time, pre/post admitted Q and actual consumed Q, bone/computed orientation, P/Q flags, and the small-motion result. Keep PHYSICAL_HIL and CUTOVER separate from scoped software PASS. RR-00F and all-CI-green are not added prerequisites for the scoped Full Reset check; known selected regression failures remain explicitly reported for parent disposition.
