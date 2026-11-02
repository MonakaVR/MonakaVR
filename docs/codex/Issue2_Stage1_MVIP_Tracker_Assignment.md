# Codex作業指示：Issue #15 / Stage 1 — 現行MVIP割当UIをBridge画面から独立

## 今回の決定（2026-10-21）

**既存のQ/6DoF割当UIはそのまま維持する。** このUIはMVIPの入力契約・割当仕様を検討するために生じたConsumer-owned機能であり、Bridge専用設定ではない。従来のSlimeVR Tracker割当UIへの全面統合やUI仕様変更は**今回の範囲外**。MVIP側の仕様が固まった後に別途設計する。

`MonakaBridgeSettings`内に以下の2種類が同居している現状のみ解消する。
- **残す**: `MonakaInputSettings` → `InputSelectionController` → `MonakaInputSocket` → Core `monaka_inputs_v1` → Consumer `MonakaConfiguration`。Q Source、6DoF Source、body、保存/適用、静的capabilitiesと動的validity。
- **後のStage #16で撤去**: `BridgeControlPanel` → `MonakaBridgeService` / `monaka_bridge_config.exe` → Bridge-owned `monaka.bridge` JSON、mapping revision、output policy、validator、`.status.json`。

両者は**ページ配置のみ同居**しており、汎用Q/6DoF割当UI自身からBridge固有設定JSONを読み書きしていない。Bridge画面を先に削除するとConsumerの入力選択UIまで失われるため、独立化を先に行う。

## 対象

- Repo: `MonakaVR/MonakaVR`、current `dev`をSHAで固定して作業開始。
- 親Issue [#2](https://github.com/MonakaVR/MonakaVR/issues/2)、Stage 1 [#15](https://github.com/MonakaVR/MonakaVR/issues/15)、Stage 2 [#16](https://github.com/MonakaVR/MonakaVR/issues/16)、独立画面の旧問題 [#3](https://github.com/MonakaVR/MonakaVR/issues/3)。
- Phase 0監査: `docs/architecture/producer-bridge-separation-phase0-20261021.md`。
- 作業前に`AGENTS.md`と既存Codex handoffを読み、入力Producer独立性を守ること。プロトコルキットは固定された既存のものを使い、private APIや独自wire codecを実装しない。

## 必須の実装変更（最小スコープ）

1. `gui/src/components/settings/pages/MonakaInputSettings.tsx`の既存コンポーネントと、`gui/src/monaka-input/controller.ts`、`socket.ts`を再利用する。保存仕様、Body/Q/6DoF選択UI、初回Pos+Q補完、Q-only、既存manualSix、保存/実行中の差分は維持。入力ソース選択ロジックを再設計しない。
2. `gui/src/App.tsx`の通常アプリProvider/`Layout`配下に、汎用MVIP入力設定への導線（例：`/settings/monaka-input`）を追加する。通常設定Sidebar/モバイル設定からアクセス可能にする。ElectronのBridge専用`/bridge`ルート、`window.electronAPI.bridge`、Bridge CLI/設定パスがなくても開くこと。Web版も対象。
3. `MonakaBridgeSettings`への`<MonakaInputSettings />`埋め込みを依存元から切り離す。Stage 2で旧Bridge画面を撤去してもGeneric入力設定へ到達できることを保証する。Stage 1でBridge専用IPC、Validator、Writer、tray等は先走って削除しない（それらはStage 2で撤去）。
4. Core `MonakaInputConfigurationApi`/`MonakaConfiguration`はConsumer-ownedである。`monaka.bridge`設定JSON、mapping、Bridge output policy/validator exeの読み書き・制御を追加しない。必要がなければCore保存処理を変更しない。
5. 既存SlimeVR Tracker一覧、`TrackerAssignment.tsx`、`SingleTrackerBodyAssignmentMenu.tsx`、SolarXR `AssignTrackerRequest`を改造して統一UIへ変えることは**禁止（今回スコープ外）**。UI統合は将来のMVIP仕様決定後。

## 検証

- 通常のMonakaVR Layout/上部ナビ/設定サイドバーで現行MVIP入力UIが表示される。画面遷移/再読込/切断/再接続、Desktop ElectronとWeb版。
- Bridgeプロセス・exe・設定JSON・診断ファイルが存在しなくても、CoreとGUIの汎用入力設定が開ける。Bridge IPCが一度も呼ばれないことをモック/スパイで証明。
- Q/6DoFの候補表示と保存、初回Pos+Q補完、Q-onlyでposition空、Slime Q+別MVIP 6DoF、手動override/cancel、offline source、unknown capabilities、同名別ID、generation conflict、saved/active/restartRequired、validity変化/再接続を既存テストと追加UI fixtureで検証。
- 通常SlimeVR Tracker画面と既存Bridge専用画面のStage 1時点の機能退行がないこと。
- `AGENTS.md`に従い、異なる**2つの独立実装**からの準拠MVIP入力とProducer不在のconsumer共通経路を検証する。独立Producer実装が準備できなければ`NOT RUN`と明示し、アーキテクチャゲート達成とみなさない。
- JDK17 `spotlessCheck`、Core/Desktop、MTP process E2E、GUI lint/build、対象のTypeScript/Node/Edge、Electron/browser smoke、GitHub Actions。実OS/HIL/SteamVRは実施しない場合`NOT RUN`。

## 完了報告 / 禁止

`dev`から作業branch、Draft PRを作り、UI導線のBefore/After・コード変更範囲・Core保存に未変更であること・テスト結果・未実施ゲートを報告。レビュー/明示承認前にmergeしない。

`dev`・`main`直接push、force-push、Bridge設定編集機能追加、Q/6DoF UIの作り直し、Slime通常割当への全面統合、旧PICO referenceソース削除、SteamVR出力Bridge削除、CI品質ゲート緩和を行わない。

Stage 2の開始条件は**汎用割当UIがBridge画面・Bridge IPCに依存せず独立した設定導線で使用可能**なこと。MVIP UIの全面リニューアルではない。
