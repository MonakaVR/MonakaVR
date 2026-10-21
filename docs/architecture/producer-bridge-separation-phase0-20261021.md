# MonakaVR Consumer / Bridge Producer 責務分離 — Phase 0 インベントリ（2026-10-21）

## 対象と確度

- **Source of Truth**: `MonakaVR/MonakaVR` `dev` `f02f43e935f9ec42387b38a71483583ef8bc2837` とルート `AGENTS.md`。
- 主Issue: [#2](https://github.com/MonakaVR/MonakaVR/issues/2)。分割作業: [#15](https://github.com/MonakaVR/MonakaVR/issues/15)（通常Tracker割当）と [#16](https://github.com/MonakaVR/MonakaVR/issues/16)（Bridge固有GUI/IPC/設定の撤去）。表示不具合の扱い: [#3](https://github.com/MonakaVR/MonakaVR/issues/3) の **B: Bridge画面撤去**。
- Bridge所有側の確認基準: `MonakaVR/MonakaBridge` `dev` `3473624a451ac172c36ea46cc5d897d84eadad43`。
- **この文書はコード経路の一次監査と移行計画**。全行静的解析、実機/HIL、通常OSでのGUI操作、配布検証を実施したものではない。Stage実装時に全参照・CI/packageの再走査を要する。

## 確認された依存と実装上の危険

| 境界 | 実在する場所 | 現状の責務 | 移行 |
|---|---|---|---|
| Electron Main | `gui/electron/main/monaka-bridge-service.ts`、`bridge-config-transaction.ts` | Bridgeの`monaka.bridge` JSON、`mapping_revision`、出力policy、validator EXE subprocess、`.status.json`と書込CAS | **Bridge所有**。Stage 2でMonakaから撤去 |
| Electron IPC / Preload | `monaka-bridge-ipc.ts`、`bridge-types.ts`、`bridge-health.ts`、`shared.ts`、`preload/index.ts`、`preload/interface.d.ts` | `BRIDGE_CONTROL`、`BRIDGE_OPEN`、`window.electronAPI.bridge` | Stage 2でBridge固有API/IPC撤去。一般IPCは保持 |
| Electron app / tray | `gui/electron/main/index.ts`、`monaka-bridge-tray.ts` | `monakaBridge` paths prefs、ファイル選択、Bridgeサービス生成/破棄、Bridgeメニュー | Stage 2。既存ユーザー設定ファイルやBridge自身のconfigは破壊しない |
| Renderer | `gui/src/App.tsx`、`MonakaBridgeSettings.tsx`、`gui/src/bridge/controller.ts`、`SettingsLayout.tsx` | 共通Providerを迂回する専用`/bridge`ルート、Bridge policy/health編集、専用設定リンク | Stage 2。残留リンク、深いURL、ショートカット、ブラウザ表示を回帰検証 |
| **Consumerとして保持すべきUI** | `gui/src/components/settings/pages/MonakaInputSettings.tsx` | Core汎用ソース/Q/6DoF選択 | **現在はMonakaBridgeSettings内の子**。Stage 1で先に通常Tracker画面へ移す |
| Consumer input adapter | `gui/src/monaka-input/controller.ts`、`socket.ts`、`server/core/.../MonakaInputConfigurationApi.kt`、`MonakaInputRpcDispatcher.kt` | `monaka_inputs_v1`、静的capabilitiesと現在validity、保存/実行中の状態、Q/6DoF選択 | **Monaka所有として保持**。可能な限り再利用 |
| 普通のTracker割当 | `gui/src/components/onboarding/pages/trackers-assign/TrackerAssignment.tsx`、`gui/src/components/tracker/SingleTrackerBodyAssignmentMenu.tsx` | SolarXR `AssignTrackerRequest`、Slimeトラッカー割当 | **Stage 1のUI結線先**。既存Slime動作を壊さずMVIPと合流 |
| 旧PICO専用Core/Desktop | `server/core/src/main/java/dev/monaka/tracking/pico/**`、`server/desktop/src/main/java/dev/monaka/tracking/pico/**` | ベンダ固有受信/ネイティブJNAの旧参照実装 | Core/Desktopの**Kotlin・Java両sourceSetsで現在除外済み**。アーカイブは後続のmigration/HIL承認まで無断削除しない |
| 正当なSlimeVR output Bridge | `server/desktop/.../SteamVRBridge.kt`、`ProtobufBridge.kt`、`server/core/.../dev/slimevr/bridge/**` | 主として既存VR出力/SlimeVR由来の内部接続 | **名前だけでProducer扱いしない**。既存SteamVR出力はMonaka consumer側で保持 |
| Protocol/build/historical | `scripts/monaka_bridge_protocol_overlay.py`、`dependencies/task4/monaka-bridge-handoff.*`、`docs/gui-desktop-20261015`、`gui/tests/bridge*` | 過去の連携・smoke・評価資材 | 実行パス、package、依存を先に判定。必要な履歴/fixture/一般ローカライズ検証は保持・再配置 |

### 重大な順序依存

`MonakaBridgeSettings`には、`inputControls = <MonakaInputSettings />` がデフォルトで入っている。`App.tsx`の`/bridge`ルートは通常の `ConfigContextProvider` / `WebSocketApiContext` / `AppLayout` 外の専用UIである。**単純にBridge画面を削除すると汎用Q/6DoF選択も消え、通常Slime割当への統合前に機能退行する。**

**Stage 1を先に実施し、通常Tracker画面で汎用入力が実際に使用可能なことを確認してからStage 2の撤去を行う。** 単に別の専用「Monaka割当画面」を追加して対応済みとはしない。

## 責務の確定

**Consumer MonakaVR:** 公開MVIPのdecode/admission、opaque identity、static capabilitiesとdynamic validityの別管理、timestamp/session/space、Q/6DoF選択、body assignment、fusion/fallback、IK、SteamVR/仮想Tracker出力とそのモード、consumer-own設定、一般診断。

**Producer / Bridge owners:** 各入力機器のprivate SDK/API/packet、機器検出/ペアリング/再起動、独自座標正規化とprofile/space approval、mapping、Bridge config/validator/transaction、Bridge service、出力route policy、Bridge tray/GUI、Bridge固有診断とキャリブレーション。

**参照実装は実行可能性の証明ではない:** MonakaBridgeには `ui/steamvr_control_gui_main.cs`・`steamvr_control_tray.cs`、`scripts/build_gui.ps1`、`tools/bridge_config.cpp`、`docs/config-write-transaction.md` がある。WPF `monaka_bridge_control.exe`・Bridge CLI・trayからoutput policy/mapping/diagnostics等を操作する構成だが、製品配布後の利用可能性・起動/復旧・設定互換性はこの監査ではNOT RUN。

## 実装フェーズとゲート

### Stage 1 — [#15](https://github.com/MonakaVR/MonakaVR/issues/15): 汎用MVIP割当を通常Tracker UIへ統合
- 独立した`monaka_inputs_v1`候補/世代制御を活用する。現行Slime `AssignTrackerRequest`とのモデル差を明示してUIアダプタ化し、IDの文字列近似で接続しない。
- Pos+Qなら初回Q指定から6DoFも自動割当、Q-onlyはQのみ、Slime Q+別6DoFを明示指定。手動選択/解除を勝手に上書きしない。
- producerなしでも通常Tracker画面がロードし、保存済みソース/無効状態/restartRequiredを表現。独立した二つの準拠Producer実装からの同一MVIP動作を試験。
- 旧`/bridge`のGUIと設定Writerはこの段階では先走って削除しない。

### Stage 2 — [#16](https://github.com/MonakaVR/MonakaVR/issues/16): Bridge固有の管理経路撤去
- MonakaBridge owner GUI/CLIの設定・診断・復旧・配布導線を検証してから、MonakaVR側`/bridge`、Electron Bridge IPC/tray/service、preload、旧設定Writerを撤去。
- ユーザー所有config・旧保存場所・校正承認・バックアップを改変しない。既存SlimeVR一般のtray/SteamVR出力を保持。
- `gui/tests/bridge/parallel-localization.test.ts`など共通機能のテストを「Bridge」というフォルダ名だけで削除しない。

### Stage 3 — 残存のベンダ固有参照、古いプロトコルoverlay、配布・HIL境界
- 除外済みPICO参照実装の依存と利用者を記録。移行/削除は実機検証、完全な代替MVIP経路、設定互換性と明示承認を得た後に別PRで判断。
- MVIPを直接送るBackend/異なるBridgeでもMonaka source catalog/assignmentsに差異がないか検証。

## 共通受入条件

- Producerの有無によらず通常GUI/Coreが起動し、選択・offline保存・再接続ができる。
- メーカー名、`publisher_id`、`source_id`等の特定値から入力型、座標変換、優先順位を推定しない。
- 二つの実装が独立した準拠MVIP Producerで、同一公開メッセージが同じconsumer経路を通る。**単一テストpublisherの別プロセス起動だけで代替しない。**
- `capabilities`と`validity`を混同しない。セッション/再送/space revision、duplicate、feedback除外を保持。
- Slime Q+別MVIP 6DoF、Pos+Q/Q-only、既存割当とsaved/active、reboot/reload、通常UIナビを回帰確認。
- JDK17 Core/Desktop、MTP E2E、GUI lint/build、専用TypeScript/Node/Edge、Electron、Android、各デスクトップpackageを対象にゲートを明示。HIL/SteamVR/実OS UIが実施されなければ`NOT RUN`。
- 既存`dev`に直接コミットしない。各段階はDraft PRで独立レビューし、完了後に統合する。

## 今回のPhase 0で実施していないこと

- コードの移動・削除、backend/bridgeの起動、実機HIL、SteamVR/UI操作
- MonakaBridgeへの別リポジトリ書込み
- MVIP新フィールド/プロトコル再定義、SlimeVR本体のトラッカーRPC変更
- ドライバ移行や旧設定の自動変更

**Phase 0の判定: 対象・順序・所有境界を特定。実装テストは NOT RUN。**
