# 追加 software check と旧 writer 競合の調査

2026-10-15 UTC。[dev 統合報告](DEV-INTEGRATION.md) の後続作業。基点はローカル `dev` **`1d5d556169f7a8c60e39f4f96e0eb9a6ff9e731b`**。製品 GUI・backend・protocol は変更せず、追加 test harness と証跡だけを保存した。remote push は行っていない。

## 起動試験の判定を分離する

| 試験 | 結果 | 確認できた範囲と限界 |
| --- | --- | --- |
| 既存 browser E2E | 前回 **2/2 PASS** | React renderer の操作。今回の native 起動の代用には使わない |
| 前回 isolated Electron fixture | 前回 **1/1 PASS** | 実 preload/IPC/service/validator と test-only renderer。製品 main は未起動だった |
| 未変更の製品 main の隔離起動 | 今回 **PASS_GUARDED_STARTUP** | 実 production main → `app://./index.html` → 実 preload/renderer → 製品 tray の実 MenuItem 呼出し → `/bridge` → 設定読込 → 正常終了。`app.isPackaged=false` |
| GUI-only test package の実 exe 起動 | 今回 **PASS_GUARDED_STARTUP** | 実 Windows exe / ASAR / `app.isPackaged=true` で上記と同じ製品起動経路・tray・画面・正常終了を確認。Core/driver 非同梱、署名なし、test entry/metadata を使う |
| guard を使わない通常起動 | **BLOCKED / NOT_RUN** | 現行 main は Core を自動探索・起動し、renderer は既存 `localhost:21110` へ接続する。確実に抑止する既存 CLI option がない。現在の非接続・非出力条件を保証できない |
| 元設定の正式 full package / installer / 署名・無制限起動 | **BLOCKED / NOT_RUN** | 元 package 設定は Core JAR・bindings provider/OpenVR assets を含む。今回はそれらの起動・実出力や通常 profile への接触を許可されていない。GUI-only 試験 package の結果をこの合格に読み替えない |
| OS tray/file picker の手動操作、HIL、実出力 | **NOT_RUN** | 自動 MenuItem invocation や screenshot で代替済みとはしない |

隔離起動は [test entry](../../gui/tests/bridge-supplemental/isolated-production-main.mjs) が先に appData/userData/sessionData/crashDumps を task-owned 一時ディレクトリへ設定し、Node の子プロセス・socket/pipe/fetch と、Chromium の外部 HTTP/WebSocket を遮断してから、**変更していない製品 main を import** する。既存 `--path` は空の task directory に向ける。これは明示的な計測用起動口で、製品の新モードではない。

両 native 起動とも実 renderer が `ws://localhost:21110/` を要求し、webRequest guard が送信前に cancel した記録がある。Core/JRE 等の子プロセス起動要求 **0**、Node socket/pipe 接続要求 **0**、page error **0**。製品ログに `server jar not found, skipping` と `SlimeVR started!` を確認した。製品の before-quit/settings/cache/window-state/logger 終了も通過した。既存サービス・標準ポートの所有プロセスは停止していない。

証跡：

- unpackaged：[result](supplemental-evidence/unpackaged-production-result.json)、[stdout](supplemental-evidence/unpackaged-production-stdout.log)、[終了](supplemental-evidence/unpackaged-production-shutdown.json)、[画面](supplemental-evidence/unpackaged-production.png)
- GUI-only test package：[build log](supplemental-evidence/package-second.log)、[ASAR 検証](supplemental-evidence/package-result.json)、[result](supplemental-evidence/packaged-gui-test-result.json)、[stdout](supplemental-evidence/packaged-gui-test-stdout.log)、[終了](supplemental-evidence/packaged-gui-test-shutdown.json)、[画面](supplemental-evidence/packaged-gui-test.png)

## 試験 package と製品の違い

locked Electron `40.9.1` の既存 cache と electron-builder `26.15.3` で、独立出力 `build/gui-supplemental-20261015/package/win-unpacked` に構築した。publish は `never`。元の `electron-builder.yml` は変更していない。試験 package は別 appId/name、guard entry、Core/driver extras 省略、unsigned directory target を使う。

ASAR に収録した **全120 product `out/` files**（main/preload/renderer/assets）を元 build と bytes 比較し一致した。guard 自体も source bytes と一致。件数・ASAR/exe の原本 SHA256 は [package summary](supplemental-evidence/package-summary.json) と [全ファイル hash](supplemental-evidence/package-result.json) に保存した。Core の `slimevr.jar` / bindings provider / OpenVR output asset は含めていない。元からある JavaVersion 判定用の小さな asset JAR は元 bytes のままで、子プロセスとして実行していない。

初回は package の tests 除外により guard entry の収録に失敗し、sanity check で止まった。task-owned staging から明示収録して修正した。その後の ASAR 検査は Windows separator に対応させ、組み上がった archive を再検証した。製品出力ファイルの欠落ではなかった。失敗ログも保存し、成功した検証後にだけ exe を起動した。

生成した exe/ASAR は ignored build の試験資産として残す。正式製品として配布・インストール・push してはいけない。

## 旧 tray/CLI の同時編集とデータ喪失

凍結 Bridge **`5fbf2a9a39450e10d4362c440bbecf2c286a4833`** の実 `monaka_bridge_config.exe` と、同じ凍結 source の **実 `SteamVrControlTray.SaveRoute`** を使い、それぞれ4つ、計 **8スケジュール**を一時 config で検証した。

旧 tray は未変更の WPF/tray/models source と小さな C# test harness を task-owned 出力へ compile した。NotifyIcon constructor、status MMF、`SetRoute`、グローバルな service-update event は呼ばず、private `SaveRoute` だけを reflection で直接実行した。そこで既存 `SteamVrControlWpf.Command` が実 native CLI を起動する。手動 tray 操作を検証したものではない。

| 外部 writer の保存タイミング | 実 CLI / 実 tray SaveRoute 共通の観測 |
| --- | --- |
| Desktop apply 前 | Desktop が全文 bytes の変更を検出して拒否。外部の `disabled / revision 11` を維持 |
| Desktop の native validation 中 | Desktop lock があっても外部 writer は成功。その後 Desktop の再比較が拒否し、外部値を維持 |
| **Desktop の最終比較と rename の間** | **双方 success、外部 `disabled / revision 11` が Desktop `both / revision 11` に上書きされる lost update を再現**。自動 backup にはどちらも元の `steamvr / revision 10` が入り、失われた `disabled` は残らなかった |
| Desktop commit 後 | 外部 writer が `disabled / revision 12` に更新。Desktop の last-loaded/saved 表示は `both / 11` のままで、reload 後に `disabled / 12` を読む。別の外部編集として扱う必要がある |

重要：**8ケースのチェック PASS は競合が安全という意味ではない**。旧 writer が Desktop lock を無視し、最終 compare/rename race と更新喪失が存在することを CLI と tray の **2ケース**で再現した結果である。タイミングは既存 service の replace test seam で制御し、その境界に実外部保存と実 rename を順番に実行した。自然発生率・実 scheduler の発生頻度は測っていない。

さらに全ケースで、旧 native serializer が未知の test extension `extension_fixture` を保存時に落とすことを観測した。これは race がなくても起きる。新しい設定 extension を旧世代 tool へ書き戻させないことが必要になる。旧 config serializer は既知フィールドから JSON を再構成するためであり、新 GUI の extension 保持や validator guard だけで外部 writer の情報欠落を防げるとは主張しない。

根拠：[race result 全8件・前後値・backup](supplemental-evidence/race-result.json)、[summary](supplemental-evidence/race.log)、[旧保存経路の harness](../../gui/tests/bridge-supplemental/LegacyTraySave.cs)、[再現 script](../../gui/tests/bridge-supplemental/race-check.mjs)。原本 hash と source 境界は [audit](supplemental-evidence/boundary.json)。

## 必要な設計判断と残件

1. **writer の所有者**：旧 tray/CLI を残すか、単一の設定 writer へ集約するか。全 writer が同じ transaction / compare-and-swap 境界に参加しなければ、GUI 側だけの lock と revision 比較では解決しない。旧 writer を止める・書換える操作は今回行っていない。
2. **設定世代と保存契約**：対応する CLI/validator の組合せ、未知 extension の保持/拒否、version conflict と recovery の規則を owner が決める必要がある。更新喪失後に古い backup を自動復元する実装は入れていない。
3. **正式な offline Desktop 起動**：Core を一切起動せず、接続先・data root を明示して product entry をそのまま安全に起動できる契約が必要。既存 `--skip-server-if-running` は「接続・起動を禁止する」option ではない。新しい製品モードは今回実装していない。
4. **手動 OS / full package / HIL**：guard なしの正式成果物、実機、実出力について別の許可・環境が必要。自動試験で合格に置換しない。
5. body/Main-Fallback/calibration の既存契約外機能、新しい排他仕様は追加していない。旧4PF receipt 原本2件欠落は **旧 integrity FAIL のまま**。

現状の運用では、同じ config は一度に1つの editor だけで編集し、旧 tool の操作後は Desktop で reload する。これは完全な排他保証ではなく、再現済み race を避けるための運用上の条件である。

## 最終 regression・所有範囲・引継ぎ

- 既存全 GUI tests **26/26 PASS、skip0、411.52ms**、全標準 lint/typecheck PASS：[TAP](supplemental-evidence/unit.log)、[lint](supplemental-evidence/lint.log)、[exit codes](supplemental-evidence/regression-exits.json)。追加 `.mjs` 全4ファイルの Node 構文・Prettier、C# compile も PASS。
- 製品 build を変更せず、その現物を unpackaged/packaged 起動し、全 `out/` bytes を検証した。以前の Bridge 49/49 を今回の新 package / P2R runtime 合格に流用しない。
- 追加差分は `gui/tests/bridge-supplemental/**` とこの report/evidence のみ。製品 `gui/src`、`gui/electron`、package/lock、runtime、protocol の前後差分0。元4 checkout は clean のまま、Bridge 全972ファイルと以前の52 receipt の原本 hash も再確認した。
- repository remote は `https://github.com/MonakaVR/MonakaVR.git`。GUI local dev と指定 Core ingress `ebdde9188b6d1a91befa22d499f3e4228d25a47b` の merge-base は **`c11dc54ed1a745ffc4b15db7f820d8d0c1993990`**。P2R 側の branch/worktree/remote dev は変更していない。
- 最新 local dev は基点 `1d5d5561` を祖先として保持する後続の test/report commit。最終応答に完全 SHA を記載する。P2R 統合側はこの最新 `dev` を保存したうえで合成手順を判断すること。こちらでは合成・push を実行していない。

再現手順は [supplemental README](../../gui/tests/bridge-supplemental/README.md)。現在の制約内で追加できる独立 software check は完了した。残件は上記の設計判断と BLOCKED/NOT_RUN の試験であり、自動試験を増やして置き換えることはしない。
