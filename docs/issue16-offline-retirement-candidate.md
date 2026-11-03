# Issue 16 isolated retirement candidate

Status: DRAFT, PREPARED_NOT_ADOPTED. G2-S remains partial and G2-P requires separate
physical, migration and operator acceptance. This candidate is not merged into
the recommended integrated Consumer/Core candidate.

Product Electron main, preload, IPC contract, tray and settings navigation no
longer activate Bridge control. `/bridge` is no longer a product route. The
independent `/settings/monaka-input` route, Core launcher, storage and existing
SlimeVR/output controls remain. Bridge implementation modules and their unit
tests remain for comparison and rollback; only historical test fixtures import
the preserved legacy preload/shared IPC contract.

The settings store still reads existing keys. No migration or deletion is
performed. The actual store test opens a synthetic cloned settings file without
rewriting it, saves an unrelated setting while preserving legacy Bridge and
unknown nested values, then restores exact backup bytes. No real user profile is
used. Restoring the previous application can reuse those retained values; live
configuration transactions still require the relevant product's CAS workflow.

Validation distinguishes product build, actual preload evaluation and real App
renderer tests from full Electron/Core bootstrap. The latter remains blocked by
live fixed endpoint ownership during this offline task. Unit/renderer success
does not establish physical output readiness or authorize production cutover.
