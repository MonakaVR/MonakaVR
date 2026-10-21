# Codex引継ぎ指示 — Issue #16 / Stage 2: Bridge管理責務の分離

## 1. Scopeと開始条件

対象: MonakaVR/MonakaVR、親Issue #2、Stage 2 Issue #16、関連Issue #3 (B: 旧Bridge画面の適切な撤去)。
最新AGENTS.mdおよびPhase 0の docs/architecture/producer-bridge-separation-phase0-20261021.md を優先する。
前段: Stage 1 Draft PR #18、HEAD 1035d0104b4cf52ae5b47a0b1642ca9ae7e6ae6c。レビュー時のdevは f02f43e935f9ec42387b38a71483583ef8bc2837。開始時に再確認する。

**最重要条件**: Consumer所有の既存MVIP Q/6DoF割当UI自体は現状維持。通常のSlimeVR Tracker割当へ統合しない。Bridgeの配置場所から独立させるStage 1が完了してからBridge専用画面を撤去する。

PR #18がdevに未統合の場合は、**まずread-onlyでBridge ownerパリティと撤去対象を確認し、実装削除を開始しない**。先行して準備する場合でも依存関係を明示し、正式マージはしない。dev/main直接変更、強制push、履歴初期化、dev1.0.0処理は禁止。

## 2. 残す機能と撤去する機能

MonakaVR Consumer側に残す:
- 既存MVIP Q Source / 6DoF Source選択画面 MonakaInputSettings、monaka-input/controller.ts と socket.ts、Coreの monaka_inputs_v1 / MonakaInputConfigurationApi / MonakaConfiguration 保存経路
- saved / active / restartRequired、静的capabilitiesと動的validity、opaque identity、session/space/replay/feedback除外
- SlimeVR既存のトラッカー一覧、Body assignment、SteamVRBridge / ProtobufBridgeなどのConsumer出力、Fusion/IK
- 既存Fluent翻訳、MVIP入力・診断・モードの共通回帰試験、独立した/settings/monaka-input導線

Bridge owner側に帰属:
- Bridge実行プロセス、専用GUI/Tray、native CLI、Bridge固有config JSON、validator実行、mapping revision、出力route policy、Bridge固有health/status、profileと入力space approval、Bridge固有較正および設定トランザクション
- MonakaVRにこれらの管理機能を残すための外部Bridge GUI起動・探索・補助プロセスを新設しない

## 3. 主要な撤去候補 (すべて参照グラフとビルド対象を再監査)

- gui/electron/main/monaka-bridge-service.ts (Bridge JSON・validator spawn・policy・status)
- gui/electron/main/bridge-config-transaction.ts
- gui/electron/main/monaka-bridge-ipc.ts
- gui/electron/bridge-types.ts と gui/electron/bridge-health.ts
- gui/electron/main/monaka-bridge-tray.ts
- gui/electron/main/index.ts 内のBridge専用Handler、service生成・破棄、Bridge設定ファイル・validator選択、monakaBridge preferences、Bridge専用tray導線
- gui/electron/preload/index.ts、preload/interface.d.ts、electron/shared.ts のBridge専用IPC・API・型
- gui/src/components/settings/pages/MonakaBridgeSettings.tsx のBridge専用画面、gui/src/bridge/controller.ts、gui/src/App.tsx の/bridge Route、SettingsSidebar.tsx と SettingsLayout.tsx の旧Bridgeリンク、Bridge専用i18n
- レガシーdeep linkに対する安全なfallbackを設け、存在しない画面への誘導、空白表示、無限redirectを防ぐ

**削除厳禁**: MonakaInputSettings、monaka-input/controller.ts、socket.ts、/settings/monaka-input、SlimeVR既存の設定・出力Bridge全般。server/coreやdesktopのpico参照アーカイブは現在ソースセット除外済みであり、移行/HIL承認なしで削除しない。

## 4. テスト移管: Bridgeフォルダ名で一括削除しない

- gui/tests/bridge/controller.test.ts、service.test.ts、ipc.test.ts はBridge固有実装撤去に同期して管理・移管・終了を判断。
- **gui/tests/bridge/input-selection.test.ts と parallel-localization.test.ts はConsumer機能を検証する試験**であり、位置がbridgeでも必ず残す。適切な一般テストディレクトリへ移動し、gui/package.json と bridge-tests.tsconfig.jsonの参照と標準pnpm testを調整。旧テスト件数を消して無条件に緑になることは禁止。
- gui/tests/bridge-smoke、gui/tests/bridge-browser、各bridge-*.config.tsと独立fixtureはBridge専用試験の範囲を精査し、現行Consumer機能をテストする部分があれば保持。
- Stage 1のgui/tests/monaka-input-route、診断GUI/モードGUI、Fluentは維持。
- CI/Gradle/パッケージ/scripts/monaka_bridge_protocol_overlay.py/dependencies/task4を解析し、実行依存と歴史資料を分ける。名前にBridgeがあるだけで削除しない。

## 5. MonakaBridge owner側の機能パリティは未確認

参照先: MonakaVR/MonakaBridge dev@3473624a451ac172c36ea46cc5d897d84eadad43。着手時に更新状況を確認する。別repoへの書き込みは禁止、改修が必要ならOwnerへ別途指示して停止する。

既存ソース上は、ui/steamvr_control_wpf.cs がmapping/profile/health/output policy/start-stop/旧設定移行を持ち、ui/steamvr_control_tray.cs は表示/Exit/route操作を持つ。scripts/build_gui.ps1はGUIをビルドし、tools/bridge_config.cpp と docs/config-write-transaction.md が共有の設定変更を支える。

**重大な仕様差**:
- 現MonakaVR ElectronはBridge設定JSONおよびvalidator exeを任意パスでファイル選択できる。
- Bridge owner WPFは原則としてroot/config/bridge.jsonとroot/build/Release/monaka_bridge_config.exeの固定配置を使う。
- したがって、GUIソースが存在することのみでは既存利用者の設定アクセス・編集のパリティを証明できない。使用中の設定の移行方法、パス指定、保存revision、profile/space承認、unknown field、バックアップ、CLI契約一致、旧tool排除を確認する。
- Bridge owner側のTray再表示/×/Exit/サービス寿命管理にはIssue #10など未解決条件がある。実OSでの一連の検証を実施しない限りPASSとしない。

Owner側GUI/Tray/CLIが既存設定の安全なアクセス方法を提供できない場合、**MonakaVRから撤去完了としない**。Owner repo改修要請を作成してBLOCKEDとする。MonakaVR内に代用Bridge管理実装を足さない。

## 6. 必須ソフトウェア・機能ゲート

- MonakaVR製品Electron main/preload/rendererを通常起動し、Bridge exe/config/health/serviceが一切なくても通常のGUI・/settings/monaka-inputが動作すること。Bridge専用IPCやproducer所有資源へのアクセスがないことを計測。正常なJava server起動・SlimeVR SteamVR出力を誤って無効化しない。
- 通常MVIP割当のQ-only、Pos+Q初回補完、Slime Q + MVIP 6DoF、manualSix、offline source、invalid/再接続、同名別Publisher、generation conflict、saved/active/restartRequiredがStage 1から退行しないこと。
- 既存Tracker画面・一般設定・tray・preload一般IPC、Core起動とプロトコル互換の回帰。
- AGENTS.mdで必須の独立した2実装の準拠MVIP Producer試験（1実装を2プロセス起動することは不可）。未実施はNOT RUNであり、アーキテクチャの全面適合PASSとはしない。
- GUI lint/build、NodeとEdge、Stage 1 Electron/browser smoke、JDK17 Spotless、Core/Desktop、MTP process E2E、Android APK、Windows/Linux/macOSパッケージ、v2ソフトウェアrelease evidenceを実施。削除したBridge試験と維持したConsumer試験を件数で区別。
- 実機HIL/SteamVR/JNA DLL/実OS Bridge GUIパリティは未実施ならNOT RUN。

## 7. 成果物・停止条件

read-onlyパリティ表、必要なBridge owner移行手順、変更前後の関数/ファイル一覧、Consumer残存確認、テスト件数差・結果、ユーザー設定保護/バックアップ、残リスクを台帳に記録。ゲート成立後は専用branchからDraft PRへ。独立監査とユーザー承認前にmergeしない。

**停止条件**: Monaka Input UIが消える、ユーザーのBridge設定・較正承認へアクセス不能になる、一般SlimeVR Tracker/SteamVR出力が壊れる、Bridge依存の回避策をMonakaに新設しなければならない、無断のプロトコル/別repo改修が必要、CIスキップで成功判定する、Producer独立性の未実施試験をPASSと誤記する。

対象外: SlimeVR共通Tracker割当UIへのMVIP統合、MVIP仕様再設計、dev1.0.0/履歴整理、旧PICOアーカイブ削除、Position Correctionの本番有効化。
