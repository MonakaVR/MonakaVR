# Codex作業指示 — WS01a現行AGENTS継承：旧履歴guardの厳密例外（再開1サイクル）

2026-10-21 / MonakaBridge WS01a Control Foundation / MonakaVR Issue #16

## 0. 目的と今回追加承認する最小範囲

直前のローカル統合候補は `tests/receiver_scope.py` における旧AGENTS.mdのバイト比較でFAILし、後続guardとpackage/CIに進めなかった。この停止を解除するため、`receiver_scope.py`・`trusted_live_source_scope.py` の**歴史的文書比較と本番対象の凍結を分離する、厳密な上流AGENTS継承例外**をレビュー・実装し、全関連保護ゲートを再実行する。

この指示は、前回の3ファイル編集制限に対する**限定的な追加スコープ承認**である。旧archive、旧baseline pin、製品実装、Protocol、既存デバイス/較正設定を変更する許可ではない。古いコード/アルゴリズム、テストassertionの削除、広範なallowは不可。

## 1. ローカル固定基準

- Owner repo: MonakaVR/MonakaBridge
- 元Draft PR #4: `refactor/ws01a-bridge-control-foundation-20261017` / HEAD `1b02d3628e11254bb0584e546c8a17713a6e6e26` / 未マージ。
- 現行Bridge dev: `3473624a451ac172c36ea46cc5d897d84eadad43`。旧base `279a01363f47ad3564b5cd8b9346180c49ee41d3`に対しAGENTS.mdだけ1コミット進んだ。
- 再開対象の**ローカル**ブランチ: `integration/ws01a-control-agents-gate-20261021` / HEAD `7e49046af07a9e38bd81eeca5462c618c577f302`（前回報告値。**GitHubには存在しないのでローカルで独立確認すること**）。
- 通常merge commit: `b6c8d2935eeffb58644386f22b3f66d76db8f4ea`。両親は `1b02d362…` と `3473624a…`。既存二つのWS01aコミット、上流AGENTSコミットは履歴保持。
- 現行AGENTS Git blob SHA-1: `6b4de2a3c7e1dd010adabb43cb7e6b8e67fb10fe`。
- 現行AGENTSのLF正規化SHA-256: `a01520ec5cf61d2abc7c9800d3e90b4f39a9ec4f4c2bb9b31aff6006b9a39736`。
- 履歴固定AGENTS blob: `5086ee762f4eb0f9c0daf781f34acfc1db6120d5`。
- 前回レポートで、元PR #4からの変更はAGENTS・現行control guard・その否定試験・対応manifestの4パス、製品1117ファイル不変と報告。**この内容はローカルHEAD未取得のため再検証すること**。
- MonakaVR側のStage 1 PR #18はDraft未マージ、Stage 2のConsumer設定撤去はBLOCKEDのまま。Bridge Issue #10、C01は別ゲート。

まず `git status`、現在のbranch/HEADと2つのmerge parent、`git merge-base --is-ancestor`、`git cat-file`、`git diff` をread-onlyで実行する。予定HEADでない、別の作業ツリーに差分がある、HIL未追跡ファイルに触れそうな場合は停止。**既存ローカル候補を作り直さない。新たなgit fetch/checkout/reset/rebase/cherry-pickやforce-pushによって保存済み成果物を置換しない。**

## 2. 根本原因：上流文書の継承と古いアーカイブ保護は異なる検証

- `tests/receiver_scope.py` (基準 `f2f0a46199af711f91c7e575c6f9229bdff51614`) は `git archive BASE` の各ファイルと、`runtime_bytes`で投影した現行ファイルを48行目でバイト比較する。`AGENTS.md`について、archiveの旧blobと現行devの新blobが一致せずFAILする。
- `tests/trusted_live_source_scope.py` (基準 `a2a5df9640ffff5dcfac67faf845cc880286142f`) も34行目に同様の比較がある。前回は**NOT RUN**であり実行FAILと記載してはいけない。
- その他、`verify_common_world_activation_scope.py`、`verify_common_world_control_scope.py`、`verify_common_world_deployment_scope.py`、`verify_common_world_live_ingress_dormancy.py`、`verify_common_world_live_sender_scope.py`等も旧archiveと比較する。これらの多くは `approved_runtime(root)` / `approved(root)` で許可済みパスを比較対象から除く。**実際にFAILしないファイルを推測だけで改造しないこと。**
- 上記archive基準のAGENTSは旧blob `5086ee…`。現行AGENTSは新blob `6b4de2…`。**過去のarchiveのAGENTSが歴史上そのままである**ことと、**作業ツリー/HEADのAGENTSが上流で承認された現行版と厳密一致する**ことを別々にASSERTする。

重要：履歴比較を通すために`AGENTS.md`を旧版へ差し戻したり、`runtime_bytes`で旧AGENTS本文へ黙って投影したり、`git archive`を加工・差替えしたりしない。旧baselineの更新、新しいAGENTSを単純に `continue` で読み飛ばす、`*.md` や `docs/**` 等を広範allowする実装は禁止。

## 3. 実装修正の権限境界

**必須確認/修正対象（局所的）**
1. `tests/receiver_scope.py`
2. `tests/trusted_live_source_scope.py`
3. `tests/control_foundation_scope.py` と `tests/control_foundation_scope_tests.py` — 前回実装の厳格上流AGENTS承認・現行/歴史baseline pinを維持し、追加guardソースの差分も**正確なパスとSHAでしか認めない**。
4. `dependencies/control-foundation-baseline.json` — 上記の現在ソースSHAのみが必要なら更新。旧BASE、実装許可19パス、旧predecessor、旧pinは変えない。

**条件付き対象**：`tests/publisher_identity_scope.py`、`tests/p2r_runtime_scope.py`、旧Common Worldの `verify_common_world_*scope.py`。対象は二次guard実行で実際にFAILしたパスのみ。必要性、既存guard間の凍結関係、原寸の旧byteを保護する代替の否定試験を台帳化し、未承認の幅広いプロトコル/製品コード変更が必要ならそこで停止して別レビューとする。

テスト専用の新規共通helperが必要な場合は**名前/入力/固定pinの所有範囲を明示**し、旧baselineやプロトコルのsource全量を再定義しない。現在の `control_foundation_scope.py::FILES` の19パスを製品機能の許可として広げない。旧guard専用の明示名集合を設ける場合も、チェック対象ファイルの現行SHA、正確な上流AGENTS blob、許可理由が一致しないとFAILする方式にする。

### 判定すべき不変条件

- 現行AGENTSの生byteが上流commit `3473624a…:AGENTS.md` と一致し、Git blobが `6b4de2…`、LF SHA256が `a01520…`。AGENTSの一文字改変・欠落・旧版復元・未承認commit版ではFAIL。
- 旧archiveのAGENTSは`git show BASE:AGENTS.md`と一致し、旧blob `5086ee…`であること。**両版の内容を同一視しない**。
- 上流文書以外は、従前の旧archive対現行ファイルの厳格比較、source-projection、capability/config/P2R pin、凍結されたテストassertionsを維持する。
- 旧guardソース自身が過去の別guardでバイト固定されている場合、**現行guardで必要な例外処理が実行されることを証明**し、歴史上のguardソース比較だけを監査済みmarkerで分離。歴史の本番コード/AGENTSを変更・投影しない。
- 保護コードの差分を任意に許す例外、捕捉例外の握りつぶし、CIテストのskip/削除、source-set除外は不可。
- 旧baselineを上書きする仕様変更はこのサイクルの成果ではない。

## 4. 必須テスト／陰性対照

A. まずread-onlyで、すべての `git archive` / `read_bytes()==old` / `runtime_bytes` を用いるPython guardの参照グラフを作る。最低限 `receiver_scope.py`、`trusted_live_source_scope.py`、`publisher_identity_scope.py`、`verify_common_world_*scope.py`。それぞれの基準SHA・AGENTS旧blob・実際の許可分岐を表にする。

B. 隔離scratchとGit差分のモック・本物のhist archiveを区別してテスト。必須負例：
- 現行AGENTS 1文字改変、削除、旧版差替え、別新SHA差替え、現行docの署名pin改変
- 凍結archive/predecessorのドリフト、AGENTS歴史比較の緩和、他の歴史テストソース変更
- control guardのrollback／自己例外化、許可外の1パス・複数パス
- capability/config/P2R旧pin変異、設定トランザクションやnative sourceの変異
- 既存9＋追加12のcontrol否定試験が欠落しないこと。新たに追加した個別陰性対照の実行件数も区別する。
- CRLF正規化の意図した許可が従来どおりで、バイト境界の比較が誤って広がらないこと。

C. **必ず実行**：`receiver_scope.py`、`trusted_live_source_scope.py`、`publisher_identity_scope.py`、関連`verify_common_world_*scope.py`、`control_foundation_scope_tests.py`、`capability_runtime_scope_tests.py`、`config_write_scope_tests.py`、P2R scope、固定provenance/asset検証。最初のFAILで中断しても、そのFAILと未実施の残りを区別し、調査用read-onlyで全候補を検査してから結論を出す。

D. 旧保護をPASSした場合のみ、前回NOT RUNになった新HEADのControl foundation 17ケース、`scripts/package_control.ps1`、実パッケージsmoke（元PR4の5ケース）、その他必要なWindows MSVC/WPF/CTest/CI受入を実施。テストは隔離専用の一時ファイル/合成configだけを使用し、旧Writerや現行サービスへ接続しない。

E. 完全なソフトウェア受入がPASSした場合のみ専用integration branchをpushして**新規Draft PR**を作成してよい。元PR #4のブランチ/HEAD/PR設定変更、dev/mainへのmergeはしない。新HEADのCIを監視・記録し、Windows/UbuntuのPython系成功だけをWPF/C++全試験PASSとは記載しない。

## 5. 停止条件／範囲外

- 旧baseline/AGENTSの巻戻し、対応範囲外のsource/validator/adapter変更を要求される。
- 受入保護の解放が広範なallowやテストskipに依存する。
- 旧Test/Sourceの固定byte保証を証拠付きで代替できない。
- user config、較正approval、HIL証拠ファイルへの読み書き、稼働中のBridge service操作が必要になる。
- PR #4や別のstacked PR #5〜#9の履歴の変更、統合、並列mergeが必要になる。

Stage 2のMonakaVR管理機能撤去、Slime Tracker UI統合、Bridge Issue #10のTray/Runtime寿命管理、C01 Protocol意味論、物理HIL/SteamVR/JNA DLL、実ユーザー設定の移行は別承認ゲート。**この修正だけでこれらを完了扱いにしない。**

## 6. 完了報告

- 固定ローカルHEAD / 元親のancestry / 最新AGENTS raw/blob/LF hash / product file byte比較
- 影響した履歴guard一覧、旧archive比較と現行AGENTS例外の正確な条件、追加のapproved fileとpin、未変更の旧baseとscope
- 追加修正ファイルの完全な一覧とdiffstat、未追跡ユーザー資産の保全
- 個別script PASS/FAIL/NOT RUN、負例件数と真正実行とモック境界、Windows CTest/GUI/package/CIの実測
- 既存PR4結果の引用と今回新HEAD結果の明確な区別
- AGENTS mandatory gates、独立producer試験、実OS/HIL/SteamVR/Installer/設定移行の未実施をNOT RUN
- 失敗ならpushなし・Draft PRなし・mergeなしで停止、local reportだけ残す
- PASSなら新integration Draft PRを提出し、元PR4と新候補の二重merge防止を提案（**まだ両方mergeしない**）
