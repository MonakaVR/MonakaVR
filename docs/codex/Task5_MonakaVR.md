# Codex Task 5: MonakaVR

単独投入用のTask 5実装指示。対象repoは `MonakaVR/MonakaVR` のみ。

**契約状態: C1 wire 1.0 candidate / master reconciliation pending.** Task 1で固定されたkitをそのまま使用し、このrepoでschema/codec/field/port/単位を独自変更しない。

## Repository / base

- repository: `MonakaVR/MonakaVR`
- default branch: `main`
- required base: `feature/pico-motion-tracker-bridge-backend`
- audited base SHA: `eabf9c196c3859007139177bef3191ac83eaf97d`
- prepared work branch: `refactor/monaka-layer-separation`
- C1 text SHA256: `3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3`

Codex Cloudでlocal branchが`work`の場合はroot `AGENTS.md` のmanaged-branch例外を適用する。branch名だけを理由に停止せず、base/tree/Task5 preparation documentsを確認すること。

## このTaskの責務

vendor-neutralなCommon Pose runtime、ObservationSourceProfile、Constraint/Fallback、body assignment、既存IKへの最小writeback、Virtual Tracker Outputを完成させる。

MonakaBridgeのMTPを新しいinput sourceとして追加し、既存Slime入力・設定・既存IK/SteamVR出力を維持する。

Task 5は以下を所有する。

- MTP consumer / inbox / adapter
- vendor-neutral `MonakaRuntime`
- Slime + MTPを同じConstraintPipelineへ投入するcomposition
- logical tracker → body target assignment
- component単位のfreshness / fallback
- `EffectiveConstraint` → 既存IKへの最小writeback
- solver/virtual-tracker output
- self-feedback exclusion
- Task 2 Phase Bへ渡す「PICO C ABI不要」の最終証跡

## 非責務

以下をMonakaVRへ持ち込まない。

- PICO API/ABI / POTB / PICO receiver C ABI
- VIVE HID/RF/raw packet
- vendor SDK/device operation
- vendor別座標変換
- MonakaBridgeが既に適用したcalibration/mount transformの再適用
- solver全面書換え
- 新しい確率Fusion algorithm

MTPを受けた後にPICO/VIVE補正を再適用しない。

## 既存状態を取り違えない

起点branchは6DoF core / constraint-resolver core / PICO native receiver/JNA / coordinate mapper / body assignment / desktop integrationを含む。

現状の`ConstraintPipeline`はIKから独立しており、PICO runtimeがpipelineを所有する構成が残っている。**pipelineへMTPが入っただけではend-to-end完成ではない。** `EffectiveConstraint`が既存IK/computed trackerへ実際に反映される回帰テストまで必要。

既存の主対象:

- `server/core/src/main/java/dev/monaka/tracking/`
  - `PoseObservation`
  - `ObservationBackend`
  - `ObservationBackendRunner`
  - `ConstraintPipeline`
  - `ConstraintResolver`
  - `ObservationFreshnessPolicy`
  - `ObservationSourceProfile`
  - `ObservationSourceProfileRegistry`
  - `ObservationStore`
  - `EffectiveConstraint`
  - `SlimeTrackerObservationBackend`
  - `SlimeTrackerPoseObservationAdapter`
- `server/core/src/main/java/dev/monaka/tracking/pico/`
  - old coordinate mapper/body assignments/native path to be migrated out of active common runtime
- `server/desktop/src/main/java/dev/monaka/tracking/pico/desktop/`
  - old native receiver/runtime/integration to replace with generic MTP runtime
- `server/desktop/src/main/java/dev/slimevr/desktop/Main.kt`
  - composition root
- `server/core/src/main/java/dev/slimevr/VRServer.kt`
  - tick integration point
- existing `HumanPoseManager` / `HumanSkeleton` / `IKSolver` / `IKChain`
  - preserve existing solver and add only the minimum generic constraint hook

## Fixed protocol / upstream handoff

Before protocol integration, read `docs/codex/task5/00_upstream_handoff_status.md`.

Task 1 fixed values:

- source commit: `5f41586b51bd84bb1cea344879b5d6325fc2c47d`
- schema commit: `04f2d6c831c68a65edfbfb66ff8838d1c9d78535`
- protocol kit SHA256: `eef5b7f2bc490926385b99dabcd44dc5a374228bf2a7869beea01f9dad936729`
- protocol.lock SHA256: `234f0dffc46b808a179ec91da3c185794d7b7c83bc5b7d2bcb31fa73886bcb44`
- C1 SHA256: `3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3`

Task 4 accepted source commit:

- `MonakaVR/MonakaBridge`
- branch `refactor/monaka-layer-separation`
- HEAD `acc329dce90dd6ba21387029cd54b6fc2d82fe8c`
- software implementation complete / hardware cutover pending

Task 4 generated handoff bytes are intentionally not inferred from source. When supplied, verify the real ZIP/manifest/report and confirm their `source_commit` is exactly the accepted Task 4 HEAD. If missing, continue only work that does not require inventing those artifacts and do not report Task 5 complete.

## C1 boundary used by Task 5

Task 5 consumes only MTP from MonakaBridge.

- MTP UDP: `127.0.0.1:29811`
- MTP convention: `rh_y_up_neg_z_forward`
- right-handed, +X right, +Y up, -Z forward
- SI units
- Hamilton quaternion, wire order xyzw
- JSON UTF-8 RFC8259, max datagram 4096 bytes
- U63 values are decimal strings; never pass through floating point
- `timestamp_ns` and `sent_at_ns` are in publisher monotonic clock identified by `clock_id`
- never subtract a remote monotonic timestamp directly from local `nanoTime()`
- map remote age once on admission: `age = sent_at_ns - timestamp_ns`, then fix local sample time as `receivedAt - age`
- duplicate/old sequence, metadata heartbeat, poll, or retransmission must not refresh pose age
- session change invalidates source cache/clock mapping/derivative history/sequence state
- coordinate space id/revision/convention mismatch must be rejected/isolated, not repaired with PICO mapper
- initial pose timeout is 500 ms unless configured otherwise

Task 5 must use the Task 1 JVM codec/API supplied by the kit. Do not implement a second JSON codec.

## MTP implementation

Add vendor-neutral MTP components, preferably under `dev.monaka.tracking.mtp`:

- `MtpObservationBackend`
- `MtpInbox`
- `MtpPoseAdapter`
- desktop `MtpUdpReceiver`

Requirements:

1. UDP parse/validation happens on a worker thread. Server tick must never block waiting for socket reads.
2. Validated messages are handed to server-thread cache/pipeline updates.
3. Internal source identity must represent `(source_id, tracker_id)` collision-free. Do not use one backend name or one body target as the source key for all devices.
4. `session_id` is lifetime invalidation state, not persistent identity.
5. MTP is incremental. Missing trackers from one datagram are not automatically removed.
6. Explicit absent/session invalidation uses the generic source-removal path.
7. Unsupported `space.id`, revision, convention, protocol/version/value is rejected and counted without killing VRServer.
8. Malformed datagrams do not escape the worker as fatal exceptions.
9. Shutdown/unregister closes receiver, removes sources, and clears derivative/assignment-generation state.

## MTP → Common Pose semantics

- valid position + confidence > 0: position available; TRACKED/DEGRADED according to MTP tracking state
- valid orientation + confidence > 0: rotation available; same state policy
- validity false or confidence == 0: corresponding component is null/LOST even if numeric fields remain
- timeout: corresponding component becomes STALE
- explicit absent/session/space invalidation: remove/invalidate source so resolver can fall back
- unknown space/revision/convention: reject; do not run old PICO coordinate mapper
- unassigned logical tracker: diagnostics only; do not invent waist/foot/body assignment

Preserve confidence/provenance as side metadata. Do not rewrite existing resolver into a probabilistic fusion system.

When converting protocol binary64 numbers into the runtime's Float/ktmath types, recheck finite/range overflow. Do not silently clamp.

Regression must prove position and orientation can come from different sources.

## Generic body assignments

Replace PICO-specific body assignment ownership with generic `TrackerBodyAssignments` or equivalent.

It maps MTP logical tracker identity to existing body target/TrackerPosition settings only. It must not inspect raw PICO/VIVE packet IDs.

Legacy PICO serial→target settings may be migrated once using explicit Bridge mapping information. Unknown correspondence is not guessed. Unassigned logical trackers remain visible diagnostically.

## One common runtime

Create/refactor a vendor-neutral `MonakaRuntime` owning exactly one:

- `ConstraintPipeline`
- source profile registry
- `ObservationBackendRunner`
- body-assignment registry

Register both existing Slime observation backend and MTP backend into the same runtime. Do not create separate PICO/MTP/Slime pipelines.

PICO-specific runtime must no longer own the common pipeline after migration.

## Desktop feature gate

Add MTP enable controls:

- CLI: `--monaka-mtp`
- JVM property: `monaka.mtp.enabled`
- environment: `MONAKA_MTP_ENABLED`
- priority: CLI → property → environment
- default: false

When disabled, do not start the MTP socket or its thread. Existing Slime behavior must remain equivalent.

Legacy `monaka.pico.*` settings may be supported only as a migration warning path. New MTP path must not require the old PICO DLL/C ABI.

## VRServer tick integration

Existing ordering is effectively:

`onTick → bridge.dataRead → tracker.tick → HumanPoseManager.update`

Do not reorder existing `onTick` behavior casually.

Run Monaka poll/resolve/writeback **after dataRead + tracker.tick and before HumanPoseManager.update**, using one small generic hook. This avoids one-frame asymmetry between Slime and MTP.

A failure in one Monaka backend must invalidate that backend/source, not crash the whole VRServer loop.

## Minimal IK writeback

This is a hard Task 5 completion gate.

Add a small vendor-neutral adapter that consumes `EffectiveConstraint` and updates existing position/rotation constraint inputs. Do not rewrite solver iterations or IK chain convergence.

Existing tracker capability flags/status are not component-specific enough to safely retain stale position during orientation-only fallback. Therefore:

- maintain a resolved component mask per target
- provide private proxy/input view when necessary so position/rotation availability matches the resolved constraint
- rebuild/register IK topology only when assignment/component mask changes, not every pose frame
- do not put these private proxies back into the ordinary raw input list where they could be re-ingested
- preserve existing Slime/HMD inputs rather than blanket-overwriting them

Inspect and minimally integrate with `HumanSkeleton.setTrackersFromList` / `IKSolver.buildChains` or the actual equivalents in the audited tree.

Required regression cases:

- head anchor behavior preserved
- pause/resume
- tracker removal
- position loss while orientation remains
- orientation loss while position remains
- source switch/fallback
- no stale old position after position component disappears

If MTP/Common Pose works but IK writeback is not complete, report exactly:

`MTP input complete / IK writeback incomplete`

and do not claim Task 5 DoD.

## Self-feedback exclusion

This is mandatory before `both` routing is considered safe.

MonakaVR must never consume its own solver/virtual tracker output as raw source input. Explicitly exclude/guard:

- MonakaBridge Direct runtime serial namespace beginning `monaka-direct:`
- MonakaVR solver output/virtual tracker serial namespace
- private IK proxy trackers

Task 5 tests must show that Bridge policy `both` does not create a feedback loop or duplicate a physical tracker into the common pipeline.

## Legacy PICO active-path removal

Only after all of the following pass:

- MTP receiver/adapter
- assignment migration
- one common runtime
- Slime regression
- component fallback/freshness
- IK writeback numerical regression
- feature gate off regression

remove the old PICO-specific active dependency from MonakaVR:

- PICO C ABI/JNA carrier for this path
- PICO packet type
- PICO coordinate mapper
- PICO-specific composition/runtime/receiver

Migrate the useful meaning of old PICO tests into generic MTP/Common Pose tests before removing them.

Do **not** remove JNA wholesale if other existing features still use it. Preserve existing project licenses/notices.

Task 5 must leave evidence that the active build/runtime no longer requires the PICO receiver C ABI. This evidence is required by Task 2 Phase B.

## Tests / build

Run the repository wrapper. Expected primary command:

```text
./gradlew :server:core:test :server:desktop:test :server:desktop:shadowJar
```

On Windows use `gradlew.bat`.

Do not report environment failures as PASS.

Required coverage:

- Task1 C1 fixture → `MtpPoseAdapter`
- identity collision `(source_id, tracker_id)`
- partial validity / confidence
- immutable sample age under duplicate/metadata traffic
- session changes / old session rejection
- source profile/freshness
- body assignment migration
- per-component fallback
- simultaneous PICO-like + VIVE-like MTP inputs
- two sources targeting same body component
- position-only loss → alternate position fallback while current orientation remains usable
- all sources lost → stale/lost
- resume removes old constraint/history rather than reviving stale pose
- duplicate / reverse order / old-new session mixture
- existing ConstraintPipeline/Resolver tests
- existing ObservationBackend lifecycle tests
- existing Slime adapter tests
- existing positional/6DoF IK tests
- pause/reset/head tests
- integration: in-memory MTP → Common Pose → EffectiveConstraint → existing IK → computed tracker numerical change
- feature gate false preserves existing Slime output behavior
- `both` route self-feedback exclusion

Hardware validation is an independent release gate. If PICO/VIVE/HMD/SteamVR are not physically exercised by this task, report `NOT RUN`.

## Definition of Done

Task 5 software DoD requires all of these:

1. MTP consumer starts without PICO/VIVE vendor DLL/library.
2. Slime and MTP enter one common runtime/pipeline.
3. Persistent logical tracker body assignment works without raw vendor IDs.
4. freshness and per-component fallback work.
5. `EffectiveConstraint` reaches existing IK and changes a computed tracker in a numerical integration test.
6. position/orientation component loss does not leave stale constraints.
7. self-generated Direct/solver/proxy outputs cannot re-enter the raw input path.
8. old PICO receiver C ABI is no longer an active dependency after migration gates pass.
9. existing Slime/IK regressions remain green.
10. final handoff records exact HEAD, build/test results, remaining hardware gates, and evidence needed by Task 2 Phase B.

Real hardware end-to-end remains separate and must not be represented as PASS unless actually run.

## Final report format

Report actual values only; do not invent future hashes.

```text
repository:
audited_base_branch:
audited_base_sha:
actual_base_branch:
actual_base_sha:
base_change_reason:
work_branch:
HEAD_SHA:
protocol_version:
schema_commit:
protocol_kit_sha256:
contract_c1_sha256:
task4_source_commit:
task4_handoff_sha256:

changed_files: each file with responsibility/reason
build_result: command / environment / PASS|FAIL|NOT RUN
unit_test_result:
mock_or_cross_language_result:
ik_writeback_result:
feedback_exclusion_result:
hardware_validation:
compatibility_status:
phase_or_DoD_status:
unresolved_issues:
followup_required_in_task2:
handoff_artifacts: filename / SHA256 / source commit
```

Also summarize which responsibilities were retained, which PICO-specific active paths were removed, and why the new path can be enabled/disabled without silently breaking the existing Slime path.