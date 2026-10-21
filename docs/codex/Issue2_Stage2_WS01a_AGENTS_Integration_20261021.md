# Codex作業指示 — MonakaBridge WS01a PR #4 / AGENTS.md統合ゲート

2026-10-21 / MonakaVR Issue #2・#16、Bridge WS01a PR #4

## 0. このサイクルの目的と厳守条件

**目的**：既存MonakaBridge WS01a Control foundationを重複実装せず、現行Bridge devの最新AGENTS.mdと履歴保持で整合させる独立統合候補を作り、旧pin/厳格scope guard/設定トランザクション/GUIパッケージの受入試験をやり直す。

実装範囲は必要最小限の「更新されたAGENTSの継承」と「それを厳密に認めるscope-guard整合・追加否定試験」。**WPF GUI、native helper、mapping、配布処理、Protocol、SteamVR driver等の新規機能開発はしない。**

ソースowner: [MonakaVR/MonakaBridge](https://github.com/MonakaVR/MonakaBridge)
- WS01a元PR [#4](https://github.com/MonakaVR/MonakaBridge/pull/4): branch refactor/ws01a-bridge-control-foundation-20261017、HEAD 1b02d3628e11254bb0584e546c8a17713a6e6e26
- PR #4の元base: 279a01363f47ad3564b5cd8b9346180c49ee41d3、PR4はその上の2コミット（43425934 → 1b02d362）
- 最新Bridge dev: 3473624a451ac172c36ea46cc5d897d84eadad43（元base後の追加1コミット、AGENTS.mdのみ）
- AGENTS.md旧blob: 5086ee762f4eb0f9c0daf781f34acfc1db6120d5、新blob: 6b4de2a3c7e1dd010adabb43cb7e6b8e67fb10fe
- MonakaVR dev: f02f43e935f9ec42387b38a71483583ef8bc2837
- MonakaVR Stage 1 [Draft PR #18](https://github.com/MonakaVR/MonakaVR/pull/18): HEAD 1035d0104b4cf52ae5b47a0b1642ca9ae7e6ae6c、限定ソフトウェア監査PASS・未マージ。これは後続Consumer撤去のGate Aであり、このOwner-only統合候補の読み取り/実装準備自体は可能。
- Bridge [Issue #10](https://github.com/MonakaVR/MonakaBridge/issues/10)のTray/GUI/owned runtime寿命管理は別ゲート。Start/Stop/Restartを実装・解禁しない。

開始時に各HEADとAGENTS.mdを照会し、変更があれば作業を止めて基準を再固定。Bridge AGENTS.mdおよび docs/codex/Task4_MonakaBridge.md、最新のWS01a設計・監査コメントを読む。古いTask4記述がAGENTSに反する場合、AGENTSを優先。

## 1. 作業ブランチとマージの扱い

- **元PR #4のsource branch/2コミットを固定して変更しない**。元ソースを消さず、別の専用統合ブランチ（例：integration/ws01a-control-agents-gate-20261021）をPR #4 HEAD 1b02d362から作る。
- その専用ブランチ上で、現行Bridge dev 3473624aを**通常のGit merge commit**で取り込む。履歴を保持し、AGENTS.mdは現行devの内容を無変更で継承する。
- Rebase/Squash/cherry-pickによる既存コミット再製造、reset/force-push、元PR #4 source/target/base編集は禁止。
- 旧PR #4と新しい統合候補を両方mergeする運用は禁止。後続の独立受入で採択候補を決めるまで両者Draft。
- **本サイクルではdev/mainへのmergeは禁止**。必要な実装は専用ブランチだけで行い、成果はBridge dev向け新規Draft PRとして提出し、親PR #4との祖先関係を明記する。もし新規PR作成権限/運用条件が満たされない場合はブランチと検証報告で停止。

## 2. 過去の厳格scope guardを壊さず更新

現行PR #4の tests/control_foundation_scope.py:
- BASEは 279a01363f47ad3564b5cd8b9346180c49ee41d3
- FILESは19件（WS01aの具体的UI/テスト/docsパス）
- verify_diff_scope()はBASEとの差分を FILES と dependencies/control-foundation-baseline.json のみに許可
- AGENTS.mdは現在の許可集合に存在しないため、devのAGENTSを履歴保持で統合すると想定外差分として扱われる。
- dependencies/control-foundation-baseline.jsonは各ファイルの現行sha256、BASE先祖の存在/sha256を固定し、旧pin dependencies/capabilities-runtime-baseline.json・config-write-baseline.json・p2r-runtime-baseline.jsonの変更を拒否する。

**指示**：
1. AGENTS.mdだけを、現行Bridge devの**正確なGit blob/sha256に一致する、明示的な上流文書更新**として受理する設計とする。一般的な docs/** や *.md のallow、ファイル名のみでの無条件例外は不可。
2. 旧BASE、旧pin、以前のWS01aで凍結したすべてのC++/C#/scripts/protocol実装、過去の証跡を維持。AGENTSを歴史的baselineのソース実装に投影して書換えない。
3. 変更が必要な現行scope guard・その否定試験・control-foundation-baselineの対応SHAのみを厳密に更新。依存する capability/config-write/P2Rの履歴再構成関数を黙って迂回しない。
4. scope mutant fixtureは一時コピーにAGENTS.mdが必要か再確認。必要ならそこにのみ追加し、**AGENTS.mdの1文字改変・欠落・別コミット版へのすり替え**、未許可ファイル変更、旧pin改変、WS01a rollback、広範な例外化をすべてrejectする否定試験を追加。
5. control scope guard自体を修正したら、LF正規化SHA・固定base存在判定・現行/歴史predecessorと、他scope guardへの影響を証跡で示す。テストskip、閾値緩和、野放図な許可パス追加ではPASSとしない。
6. 注意：PR #4へのdevの追加は文書だけなので、製品機能変更が必要になった場合はその理由を確定し、そこで停止して別レビューを求める。継承した最新AGENTSと整合しない新たなProducer固有依存を絶対に持ち込まない。

## 3. WS01a機能維持とアーキテクチャ適合

現行候補の任意config/health/helperパス、installation profile、default read-onlyと明示write許可、native helperのtransaction-version確認、runtime_contract負例probe、exact-byte CAS/バックアップ/old writer競合時のfail-closed、bounded helper process、保存結果不明時のreload/review、diagnostic freshness、GUI/Tray表示とControl packageを保全。

- 二つの合成的に異なるMBIP source identityでも、Bridgeのcatalog・mapping・MTP/SteamVR output adapterはProducer名による分岐をしない。AGENTSのmandatory gatesを個別にPASS/NOT RUNで記録。二つの「独立実装」Producerのエンドツーエンド試験がなければMonakaVR側の更に厳しい受入ゲートはNOT RUN。
- Bridgeの設定helperはBridge自身のconfig writerであり、入力Producerのexe、process、private SDKへのアクセスではない。この境界を静的に確認する。
- Producer有無による起動条件、静的capabilitiesと現在validity、source_id/device_idのopaque lookup、unknown device descriptor、session/reacquisition、feedback除外、output adapterの独立性を検証。
- protocol C01 publisher_id意味論は未解決の独立ゲートであり、WS01aに便乗してschema/wire/identityを変更しない。実機やvendor固有デバイスのHILは未実施ならNOT RUN。
- **Issue #10を取り込まない**：現在のStart/Stop/Restart disabledは保持し、不明PIDや同名実行ファイルを停止しない。既存GUIの前面復帰・Exitの不備は残件として報告。

## 4. 必須再検証（旧報告を今回の実行結果として数えない）

1. merge ancestry/差分: dev側AGENTS文書コミットとWS01a元2コミットが通常Git mergeによる祖先であること。通常製品ファイルのPR #4 HEAD比較がbyte-identicalであること（変更必要なら差分説明）。
2. PR #4の厳格scope guard：tests/control_foundation_scope_tests.py、tests/capability_runtime_scope_tests.py、tests/config_write_scope_tests.py と関連 protected scope/fixture。一切の広範allowを設けない。既存9種のcontrol mutantと追加AGENTS改変/欠落/別版拒否を確認。旧capability/config/P2R pin byte一致。
3. 新規候補HEADからWindows x64 MSVC Release GUI/native helper build、selected CTest、および scripts/build_gui.ps1、tests/control_foundation_tests.ps1、tests/ui_production_tests.ps1、scripts/package_control.ps1、tests/control_packaged_smoke.py を安全な新規一時ディレクトリ・テスト用configで再実行。旧CLIによる同一設定の共同編集やユーザーデータ書換は禁止。
4. PR #4の元の受入結果（対象の CTest 5/5、native transaction process 16、Control foundation 17、packaged startup 5/5）は引用可能だが、今回の新HEADについて再実行した件数と明確に区別する。
5. GitHub Actions Windows/Ubuntu CIの全チェック/sha/jobを確認。Python release/P1チェックをC++/WPF/HILの実行と誤称しない。
6. AGENTS受入：no specific producer installed、2 synthetic MBIP identities、unknown descriptor/freshness/identity、MTPとSteamVR別adapterの各ゲートを明記し、実施していない項目はNOT RUN。
7. 実OS visible manual UI、installer、通常Bridge service寿命、SteamVR物理出力、HIL、JNA native binding、旧MonakaVRからの**実ユーザー**path migrationは未実施ならNOT RUN。「WPFがソース上存在」「hidden desktop smokeがPASS」は同等ではない。

## 5. 完了報告と独立レビューへの引渡し

- 基点SHA / HEAD / ancestry図 / diffstat / 元PR #4の変更からの追加パス
- 最新AGENTS blob照合、scope guardへの追加許可の**正確な根拠**と、反例reject実験
- 製品コードbyte比較、各scope pinの固定・改変箇所
- WS01a設定/GUI/配布ソフトウェアの各試験実測件数、CI run/結論、NOT RUN残件
- Architecture AGENTSの各要求のPASS / FAIL / PARTIAL / BLOCKED / NOT RUN
- Bridge Issue #10、C01、MonakaVR #18、実ユーザーconfig移行の未完了区分
- 元PR #4と新候補PRが重複で別個にmergeされないための後処理提案（**まだ実行しない**）
- 専用ブランチでDraft PR作成。review/正式mergeは必ず人間の明示承認後。

**この作業が通ってもMonakaVR Issue #16のBridge設定管理コード撤去は開始しない。** OwnerのGUI移行・Issue #10・Consumer Stage #18統合を別ゲートとして残す。
