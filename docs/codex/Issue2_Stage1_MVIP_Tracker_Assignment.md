# Codex実装指示：Issue #15 / Stage 1 — 通常Tracker画面へのMVIP入力統合

## 対象と前提

- Repository: `MonakaVR/MonakaVR`。着手時に現行`dev` HEADを読み取り、そのSHAを報告する。
- Issues: [#2](https://github.com/MonakaVR/MonakaVR/issues/2)、[#15](https://github.com/MonakaVR/MonakaVR/issues/15)。次段階は[#16](https://github.com/MonakaVR/MonakaVR/issues/16)。
- Phase 0依存関係と境界: `docs/architecture/producer-bridge-separation-phase0-20261021.md`。
- 先に`AGENTS.md`、`docs/codex/Task5_MonakaVR.md`、`docs/codex/task5/00_upstream_handoff_status.md`を読む。古いProducer専用タスク指示より最新`AGENTS.md`を優先する。
- 目標は**MonakaVR Consumerの通常Tracker UIで、Bridgeを知らずにMVIPトラッカー/Q/6DoFを扱えること**。Bridge側の機器操作、Native SDK、設定編集は追加しない。

## 実装順序

### 1. 現行経路の再確認
以下を読み、状態/責務の所有境界を図示する。

- `gui/src/components/onboarding/pages/trackers-assign/TrackerAssignment.tsx`
- `gui/src/components/tracker/SingleTrackerBodyAssignmentMenu.tsx`、`TrackerSettings.tsx`、`TrackersTable.tsx`
- `gui/src/components/onboarding/BodyAssignment.tsx`、`TrackerSelectionMenu.tsx`
- `gui/src/components/settings/pages/MonakaInputSettings.tsx`
- `gui/src/monaka-input/controller.ts`、`socket.ts`
- `server/core/src/main/java/dev/monaka/tracking/MonakaInputConfigurationApi.kt`、`MonakaInputRpcDispatcher.kt`、`TrackerBodyAssignments.kt`
- `server/core/src/main/java/dev/monaka/tracking/mtp/MtpObservationBackend.kt`
- `gui/src/components/settings/pages/MonakaBridgeSettings.tsx`（汎用InputPanelが入れ子になっている、という現状確認のみ）

既存SlimeのSolarXR `AssignTrackerRequest`とMonaka `monaka_inputs_v1`の**識別空間・世代・保存状態・副作用の差**を設計に明示し、名前ベースの突合を禁止する。

### 2. 通常UI統合
- 現在のSlimeVRトラッカー一覧と既存部位割当操作にMVIP入力候補を結合する。独立した「Monaka割当専用ページ」を新設して終わりにしない。
- MVIPトラッカーは`publisher_id/source_id/tracker_id`をopaque identityとして扱う。Slimeの物理TrackerIDへ曖昧に変換して重複・同一視しない。
- Q Sourceと6DoF Sourceの選択はCore側の静的capabilitiesに基づく。Pos+Q初回自動補完／Q-onlyのPos不補完／Slime Q＋別MVIP 6DoF／手動選択・解除の保護を実装。
- Core RPCの`generation`、restartRequired、savedMode vs activeMode、conflict、availabilityのfail-closed規則は現行契約を利用。モード/割当の保存を二重のWriterで実装しない。
- Producer不在なら空カタログ/既存保存済みoffline表示とし、通常Slime操作を邪魔しない。Bridge EXE/config/health/serviceの有無を問い合わせない。
- `/bridge`内部の汎用`MonakaInputSettings`が唯一の割当導線にならないよう移し、通常UIで使用可能なことをテストする。ただしStage 2以前に旧Bridge画面・管理IPC・trayの全削除はしない。

### 3. 自動/回帰試験
最小必須ケース：
1. Pos+Q capability入力→初回Q指定でQ/6DoFを設定
2. Q-only入力→Qのみ、6DoFはnull
3. Slime Qを残し、別MVIPの6DoFを指定/解除
4. capabilityは維持しvalidityだけ一時invalid→割当が勝手に消えない
5. same tracker display name across 2 publishers / IDs do not collide
6. 既存割当vs新選択の置換確認、manualSix、cancel、save/apply、restartRequired、設定復元
7. Bridge executable/config/serviceなしでCore/GUI通常操作
8. **異なる2つの実装**の準拠MVIP Publisherで同一データが同じconsumer処理になる。単一Publisherの2プロセスだけでは達成として認めない。既存のバージョン固定Protocol kitを使う。新たな私有Codecを足さない
9. Userが通常BodyAssignmentを使う際、旧Slime IMUの操作やTracker表示が劣化しない
10. 検証中のFail, Crash, Unknown capabilities/invalid IDに対するfail-closed

JDK17 `./gradlew spotlessCheck :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --no-daemon --console=plain`、GUI lint/build、既存のNode/Edge、必要ならElectron smoke、GitHub ActionsのFull Buildとv2 release evidenceを実行して記録する。実OS GUI、SteamVR、PICO/VIVE実機は別ゲートであり未実施ならNOT RUN。

### 4. 発表・レビューゲート
- 独立branchから`dev`へDraft PR。元の`dev`や`main`を直接変更しない。
- 変更ファイル/責務区分、ソース識別・保存のデータフロー、既存設定への互換、試験件数/ログ、実機未検証とユーザー操作を報告。
- 独立監査完了前にmergeしない。
- Stage 1が成立しない限りStage 2でBridge専用GUIを先に消さない。

## 禁止
- Bridge専用設定JSON、EXEパス、出力policy、mapping、calibration、serviceの管理をConsumerへ追加・維持する方向への機能拡大
- Producer名やメーカー名による特別処理、private SDK/API、追加座標変換、暫定wire parser
- `server/.../pico/**`旧参照実装の無断削除（現状source set除外）。関連する場合は独立Issue・レビュー
- 正常なSlimeVR `SteamVRBridge`・Protobuf出力・IK/Fusionの巻き添え削除
- CIチェック無効化、テストskip、意図しない設定・キャリブ承認変更
- unrelated branch削除/force-push/history rewrite
