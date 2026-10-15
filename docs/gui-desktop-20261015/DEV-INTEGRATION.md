# GUI-only dev 統合の最終報告

2026-10-15 UTC。ユーザーの「dev で」という明示指示に従い、既存 **MonakaVR/MonakaVR のローカル `dev`** へ GUI 改善を merge した。merge 前・後とも、unit/IPC 26件、画面/Electron/tray smoke 3件、全体 lint/typecheck/build が PASS。**remote push は行っていない**。

実装した機能は、既存 Desktop の Bridge 設定画面、4種類の output policy の review/apply/cancel/失敗復元、saved/observed 設定の分離、鮮度・identity・tracking・成分 validity の診断表示、同じ画面を開く tray entry。4領域の実装済み機能・backend/API・不足・保留理由、旧テスト意図の対応付け、P2R/Core 適合性、スクリーンショットは [詳細 report](REPORT.md) に記録した。

## 統合前後の SHA と checkout

| 項目 | SHA / 状態 |
| --- | --- |
| remote `origin/dev`（直前の再確認） | `c11dc54ed1a745ffc4b15db7f820d8d0c1993990` |
| ローカル `dev` 統合前 | 同上。新しい task-owned worktree に作成し、clean を確認 |
| GUI-only 候補 branch | `codex/gui-dev-candidate-20261015` / `160fbf4ce09434a1abf37542a46a09b5298b115d` |
| merge commit / 統合後の製品 SHA | **`4d9836077dd0ab9e598d7a215d7287057e79ea01`** |
| merge parents | `c11dc54ed1a745ffc4b15db7f820d8d0c1993990` + `160fbf4ce09434a1abf37542a46a09b5298b115d` |
| 最終 `dev` HEAD | merge の後に本 report/evidence のみを commit。`git log -1 dev` で確認できる。GUI 製品 tree は上記 merge SHA と同一 |
| 最終 dev worktree | `C:/Users/nynyp/Documents/ChatGPT/MonakaVR_UI/.gui-work/MonakaVR-Desktop-dev` |
| GUI-only 候補 worktree | `.gui-work/MonakaVR-Desktop-dev-candidate` |
| 当初の調査/実装 clone | `.gui-work/MonakaVR-Desktop` / `codex/gui-bridge-controls-20261015` / `95f0c39a883e46a03bed58bea7a659cca9c3dd46`。その branch は切替・merge せず保存 |

`dev` は prepared base `17c4c9b` より前の履歴だった。当初の branch 全体を merge すると runtime の履歴も含むため、その操作は行わなかった。base→dev の GUI、package/lock、SolarXR の元内容は同一と確認し、**GUI・テスト・証跡の4 commits だけ**を `dev` 上へ移植した。競合解消は不要だった。

| 当初 GUI commit | dev 基準の対応 commit | 内容 |
| --- | --- | --- |
| `a65e4fbf809b8a1e3187f591991cd12207f5a732` | `cc9831b160769bc221e5532c63d525ce9c0b9848` | 既存 Desktop GUI、service/controller/health/IPC/tray、全テスト |
| `27cf7ce9b5ce6b42002bfbeb1348d159f40f94e4` | `44a7f61349133cfdaf2eccc0a3f925e1c62fd593` | 調査 report・検証証跡 |
| `069ec3ba4b48b974c3a5b2b0561c3506429e222f` | `254140fe296d339d18b52b3d1c6da88ffd4e6d4e` | Windows checkout でも captured receipt の元 bytes を維持 |
| `95f0c39a883e46a03bed58bea7a659cca9c3dd46` | `160fbf4ce09434a1abf37542a46a09b5298b115d` | 独立 smoke port を test-only 環境変数で指定可能にする |

移植時に既存 `dev` commit 自体を指定した空 cherry-pick が1回発生したが、差分が空であることを確認して skip した。取り込んだ履歴は上表の4 commits のみ。履歴 rewrite/reset/amend はしていない。receipt の CRLF 差分は元 bytes と hash を確認して再現し、元証跡は改変していない。

## 統合境界と他作業への影響

- `dev` に追加した path は `gui/**`、GUI dev dependency の `pnpm-lock.yaml`、`docs/gui-desktop-20261015/**` のみ。
- `server / src / third_party / dependencies / solarxr-protocol / root package.json` の前後差分は **0**。prepared runtime 系譜を merge した事実もない。
- GUI tree `ccefce304c26ee67b32799a4a5f7fd759cd67432` は当初候補・GUI-only 候補・merge 後で一致する。merge tree 自体も検証済み GUI-only 候補と一致する。
- P2R の Bridge/Core の `dev` 統合は行わない。指定された `42ec6c809c085a44cdb0d331f2bab452c79701fd` / `ebdde9188b6d1a91befa22d499f3e4228d25a47b` は契約確認の参照としてのみ使用。別タスクの branch/worktree には操作していない。
- 元 Bridge `5fbf2a9a39450e10d4362c440bbecf2c286a4833`、Protocol `0b4a27c2e2de88705c92fb6dbf5a8d9ca752bf39`、PICO `deb455fe02e4eae1589e41fec3b574925f6b43b0`、VIVE `2cfa10f321b62fad4fc99c690f05d339ef91bab4` は前後同一、全 clean。元 checkout の branch 切替なし。
- Bridge 全 tracked regular file **972件**を再度 SHA256 照合し差分0。元証跡 manifest も全一致。固定 Core/JAR、Normalizer、protocol ingress/conversion、Fusion/recovery、wire semantics、Admission Gate、frozen package、shared runtime build output は変更していない。

根拠：[merge 後 audit](dev-evidence/boundary.json)、[read-only audit script](dev-evidence/collect_integration.py)、[merge 前候補境界](dev-evidence/candidate-boundary.json)。原本ログ/JUnit の whitespace は証跡として維持し、製品 `git diff --check` は GUI/lock 差分に対して PASS。

## merge 前後の実測チェック

| 確認 | GUI-only 候補 / merge 前 | ローカル dev / merge 後 |
| --- | --- | --- |
| 全 unit/service/controller/IPC suite | **26/26 PASS、skip0、439.16ms** | **26/26 PASS、skip0、393.71ms** |
| 全 GUI lint/typecheck/prettier | PASS | PASS |
| 製品 Electron main/preload/renderer build | PASS | PASS |
| smoke entry/config TypeScript + formatting | PASS | PASS |
| test-only Electron entry build | PASS | PASS |
| 実 renderer + Electron/preload/native validator/tray MenuItem smoke | **3/3 PASS、22.3s** | **3/3 PASS、19.1s** |
| receipt bytes / protected runtime diff | 一致 / 0 | 一致 / 0 |

merge 前：[exit codes](dev-evidence/candidate-check-exits.json)、[unit TAP](dev-evidence/candidate-test.log)、[smoke](dev-evidence/candidate-browser.log)。merge 後：[exit codes](dev-evidence/check-exits.json)、[unit TAP](dev-evidence/test.log)、[lint](dev-evidence/lint.log)、[build](dev-evidence/build.log)、[smoke 型検査](dev-evidence/check-bridge-smoke.log)、[fixture build](dev-evidence/smoke-bridge-desktop-build.log)、[smoke log](dev-evidence/browser.log)、[Playwright JSON](dev-evidence/browser-result.json)。

別途、Bridge の変更しない全49 CTest は **49/49 PASS、skip0、43.60s**：[result](evidence/bridge-result.json)、[JUnit](evidence/bridge-suite.xml)。GUI を Bridge に混ぜずに維持した gate の証跡であり、新 P2R/4PFR runtime 全体の受入れ結果ではない。merge は GUI-only なので Bridge source/972 hash の再確認を行い、同じ suite を根拠なく反復していない。

候補と dev はそれぞれ frozen/offline dependencies、新しい build output、別ポート **4183 / 4184** を使用。既存ポートの所有プロセスを停止していない。通常の Electron main や Core/JAR は一度も起動していない。

merge 後のスクリーンショット：[current](dev-evidence/desktop-controls-current.png)、[historical](dev-evidence/desktop-controls-historical.png)、[saved](dev-evidence/desktop-controls-saved.png)、[実 Electron IPC/tray 導線](dev-evidence/desktop-native-ipc-tray.png)。実 Tray/MenuItem は programmatic invocation まで。OS tray/file picker の手動操作、packaged app、HIL、実機計測、SteamVR/VRChat 出力は **NOT RUN**。

## 残件と公開範囲

旧 tray/CLI は Desktop の transaction lock に参加しないため最終 compare/rename race は残る。body/Main-Fallback と calibration の structured 契約・実機検証も保留。詳細な backend 依存は元 report に記載した。

**旧4PF の2 receipt 原本欠落は旧 integrity FAIL のまま**。今回の GUI、P2R validator、49 CTest の新証跡で補完・置換したことにはしない。本線の新 P2R / 4PFR 証跡とは別の判定である。

現時点で公開してよい候補を示すなら、**既存 MonakaVR repository のローカル `dev` 上の GUI-only merge と report/evidence commits**。当初 prepared base の `codex/gui-bridge-controls-20261015` を直接 `dev` に merge/push してはいけない。未採用 Bridge GUI 案も対象外。新 GitHub repository の作成、remote push、PR、deploy は行っていない。
