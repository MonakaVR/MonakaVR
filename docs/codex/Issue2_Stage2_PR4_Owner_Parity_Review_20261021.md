# Codexへの指示 — Issue #16 Gate B / MonakaBridge PR #4 独立監査

## 対象・停止条件

今回はREAD-ONLY監査と次実装の判断材料を作る1サイクル。コード変更、設定変更、サービス操作、PRのmergeは実施しない。MonakaVRのStage 2管理機能撤去を先に行わない。

- MonakaVR dev: f02f43e935f9ec42387b38a71483583ef8bc2837
- Stage 1 Draft PR #18: 1035d0104b4cf52ae5b47a0b1642ca9ae7e6ae6c (独立レビューは限定PASS、未マージ)
- Phase 0 / Stage 2 docs Draft PR #17（この文書の作業元）
- MonakaBridge dev: 3473624a451ac172c36ea46cc5d897d84eadad43
- MonakaBridge WS01a Draft PR #4: 1b02d3628e11254bb0584e546c8a17713a6e6e26
- MonakaBridge Issue #10: GUI再表示／×／Exit、所有runtimeの寿命管理
- 主Issue: MonakaVR #2 / #16、関連 #3 / #15。

作業開始時に全SHA・Issues・AGENTS.mdを照会して基準固定。変更があれば照会結果を優先し差分を記録する。

## 重要な新発見 — 既存候補を再利用

以前のStage 2監査では、Bridge dev上のWPFはroot/config/bridge.jsonおよびroot/build/Release/monaka_bridge_config.exe固定であり、MonakaVR旧GUIの任意config/validator選択とパリティFAILと判断した。

**しかし未マージのMonakaBridge PR #4 / WS01aが、この差の大部分に対応する候補を実装済み。重複したGUI/CLI管理機能を新規開発してはいけない。**

PR #4の確認済み実装範囲：
- ui/installation_control.cs の明示的なinstallation profile、--config、--health、--config-helper、--helper-sha256、--allow-write / --read-only、旧root形式の互換性。
- ui/steamvr_control_gui_main.cs / steamvr_control_wpf.cs / steamvr_control_tray.cs はWPFとTrayが同一install targetのconfig/health/helperを扱う。
- native CLIのtransaction-version = monaka-config-transaction-v1確認、exact-byte CAS、revision・backup・unknown member保持、timeoutと保存結果不明時の再読込要求。
- scripts/package_control.ps1 はControl GUI/helper/profile例・SHA-256 manifestを梱包する。Bridge service/driver/installer全体の出荷成立性は証明していない。
- PR #4はstart/stop/restartを所有権不明として明示的にdisabledとしている。Issue #10の要件は未充足。

PR #4の比較では元base 279a01363f47ad3564b5cd8b9346180c49ee41d3から現行Bridge dev 3473624a451ac172c36ea46cc5d897d84eadad43へ進んだ差はAGENTS.mdの1コミットのみ。PR #4はdevとdivergedしているため、候補を正式に扱う際には更新されたアーキテクチャ規則を反映し、履歴保持の再検証が必要。現状のGitHub conflict判定だけでは規則適合PASSにならない。

## 監査項目

1. **任意config/helper移行**: MonakaVR旧Electronで指定できた任意JSON、native validator、.status.jsonをWS01a GUI/CLIで選べるか。absolute path、installation profile、選択後の再開、read-only初期値、明示書込許可、複数installation、旧保存値の安全な持出しを検証。
2. **配布レイアウト**: repo root不要でControl packageから起動、helper SHA pin、旧CLI混用禁止、manual visual・installer/署名・runtime service展開はNOT RUNと分ける。
3. **保存と診断**: ConfigHelperClientの実helper互換性、runtime_contract検証、CAS/lock/revision、unknown member/approval、timeout/cancel/child cleanup、診断freshness/publisher/revision/future/stale、不確定保存後のreload。ソフトウェア試験と実ユーザーJSON適用を区別。
4. **UIの寿命管理**: WPF閉じる/Tray再表示/前面復帰/Exit、サービスの起動停止所有権をIssue #10に沿って比較。PR #4はruntime start/stop disabledなので「完了」と判定しない。他者の同名プロセスや不明ownershipのサービスをkillしない。
5. **入力Producer非依存**: 最新Bridge AGENTS.mdに従い、MBIP Producerのprivate SDK/実行ファイル/設定/探索/起動の追加アクセスがないこと。MonakaVR側は既存MVIP Q/6DoF UI、monaka_inputs_v1、SlimeVR SteamVRBridgeを保持する。
6. **プロトコル境界**: PR #4のdocs/ws01a-audit-correction.mdで挙がるC01 publisher_id意味論は別のプロトコル所有者の判断が必要。wire/schemaを勝手に変更しない。
7. **重複実装の検知**: Bridge PR #4、Issue #10、現在のDraft PR #5〜#9等が担っている機能と新タスクが衝突しないことを確認。

## 証拠と試験

PR #4のPR本文は、MSVC/WPF build、selected CTest、actual packaged GUIのprivate hidden desktop smokeをソフトウェアPASSと記載する。GitHub ActionsはWindows/Ubuntuのfail-closed release/P1系で、すべてのC++/WPF/HILのCI代替ではない。

実施していないテストは NOT RUN。現在の1サイクルでread-only参照しただけのlocal test結果を再実行PASSとして数えない。独立2実装Producer、実機、SteamVR、操作中の実OS可視GUI、インストール済み環境、通常Service寿命管理を区別する。

PR #18のStage 1はソフトウェア限定PASSだが未統合。Stage 2実装は引き続きBLOCKED。

## 次フェーズのゲート

- Gate A: PR #18が明示承認されdevへ統合され、独立MVIP割当画面が通常設定で使える。
- Gate B: PR #4相当のOwner GUI/CLI/Control packageが最新dev+AGENTSに対して独立レビューされ、安全な既存config/validatorパス移行が成立する。
- Gate C: Issue #10のowned runtime処理、GUI再表示・×・Exit・停止完了、運用導線が承認される（unsupportedと表示するだけでは寿命管理機能の完成ではない）。
- Gate D: Consumer側の既存Q/6DoF UI・Core保存・Slime/SteamVR出力が保たれた上で、別PRによりBridge専用管理IPC/Writer/Tray/Routeのみ撤去。2独立Producer試験/HIL未達ならNOT RUNのまま。

全ゲートを勝手にPASSとせず、ユーザーの明示承認があるまでどのPRもmergeしない。

## 終了報告

結果を PASS / FAIL / PARTIAL / BLOCKED / NOT RUNで表にし、根拠commit、該当ファイル、テスト実測/引用を記載。次のCodexへの指示は既存PR #4の再レビューとOwner Issue #10の不足点に分けて出す。

このサイクルのwrite/commit/PR/merge/service操作数を0として完了すること。実装変更が必要な場合は追加承認を求めて停止する。
