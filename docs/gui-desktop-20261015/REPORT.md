# Monaka Desktop GUI 調査・実装・適合性確認

2026-10-15 UTC。既存の **MonakaVR/MonakaVR `gui/`（Electron/React Desktop）** に Bridge の設定・診断画面を実装した。独立ローカル clone/branch に保存し、Bridge の凍結ソースへ GUI 差分を入れていない。新しい GUI repository・二重アプリは作成していない。

GUI 全体 lint/typecheck/build、service/controller/IPC **26/26 件**、実画面 smoke **3/3 件**、別途の凍結 Bridge full CTest **49/49 件** が PASS。いずれも skip なし。実機・実 tracking 出力・runtime 本線の統合結果は含まない。

## 所有範囲と保存先

| 項目 | 値 |
| --- | --- |
| Repository | `https://github.com/MonakaVR/MonakaVR.git` |
| 独立 clone | `C:/Users/nynyp/Documents/ChatGPT/MonakaVR_UI/.gui-work/MonakaVR-Desktop` |
| Local branch | `codex/gui-bridge-controls-20261015` |
| prepared base | `17c4c9bb712b425bd72911837c7df010dee5bd62`（`refactor/monaka-layer-separation` 系譜） |
| 実装 commit | `a65e4fbf809b8a1e3187f591991cd12207f5a732` |
| 製品差分 | `gui/**` と GUI dev dependency の `pnpm-lock.yaml` のみ |
| 証跡 | この report と `evidence/`。証跡 commit はこの branch の後続 commit |
| 公開 | local commit のみ。push / PR / merge / deploy なし |

最初にローカル checkout・関連 source・AGENTS・Task5/handoff・関連 GUI 設計と GitHub の既存 repository を調査した。既存 owner はこの repository の `gui/electron/main/index.ts`、`gui/src/App.tsx`、settings/tracker/onboarding UI。MonakaVR 側には既存 tray と設定 store もある。独立 Desktop 専用 repository は確認できず、既存 GUI を優先した。現在の source と AGENTS を優先し、過去 report は背景として扱った。関連 checkout 内に追加の scoped AGENTS / `.agents/skills` は見つからず、ローカル `memory_summary.md` も確認できなかった。

初期 WPF 案は別 clone `.gui-work/MonakaBridge` の `6700d6304fac6ce7af5dd51ec41b32537c9ad35c` に **未採用** として保存している。同案の scope 8 violations は実際の新規違反だったため、gate を弱めず製品差分から外した。今回の GUI 実装をその Bridge branch へ適用しない。

## 4 領域の調査と変更前後

| 領域 | 既存の実装・利用できる契約 | 未接続・不足・バグ／実機依存 | 今回の結果 |
| --- | --- | --- | --- |
| トラッカー割り当て | Bridge `ui/control_models.cs` の MappingEditor と native config validation が `(source, device)` mapping、source 内 tracker 重複、保存・復元を扱う。MonakaVR `TrackerSettings.tsx` は既存 SolarXR `AssignTrackerRequestT` で body role を送る | Bridge mapping と MonakaVR body role/Main/Fallback は別責任。Bridge に body-role setter はない。PICO serial・VIVE stable identity/RF・物理接続は HIL 待ち | 新 Desktop は診断の publisher/source/device、logical tracker、tracking、pose freshness、position/orientation validity、collision を別表示。identity 選択は並べ替え・stale 更新で維持。mapping と profile/input-space approval を別々に表示。mapping/body role の書き換えは追加しない |
| Bridge GUI 表示 | Bridge `healthSnapshot` と隣接 `.status.json` は publisher、policy、revision、native observation の device/pose age/fresh/collision を公開する。既存 GUI の Start/Stop は process 起動操作 | backend started / publisher running / downstream delivery を証明するフィールドはない。ファイル mtime や fresh pose だけで healthy にできない | backend/publisher は常に `unknown`。current 診断・native source flow・saved/observed policy・revision の一致を分離。wrong publisher/config、missing/future/older timestamp、切断、経時 stale を拒否し、保持行は historical と表示 |
| 出力プロファイル／policy | 既存 `steamvr / monaka / both / disabled`、config v2、`monaka_bridge_config <file> validate`、mapping_revision。Bridge service が設定を再読込する | 旧同期 UI に review/cancel/再入防止が不足。保存と runtime 観測・実配信は異なる。旧 tray/CLI は別 writer | 既存4値を選択→明示 review→非同期検証・保存。重複クリック防止、操作中編集禁止、取消、失敗復元・再試行、設定場所の永続化、byte conflict 検知、backup、atomic replace を実装。新 route/output semantics は作成しない |
| キャリブレーション | Bridge static/trajectory CLI、solver/group apply、native align が存在。MonakaVR AutoBone は既存 `AutoBoneProcessRequest` / status / epoch / apply の GUI を持つ | Bridge 計測は OpenVR reference と interactive stdin/text に依存。structured progress/cancel/result/apply の GUI 契約がない。`--apply` は再測定であり過去結果 apply と同一ではない。AutoBone も Bridge world calibration の代用にはならない | 前提・承認状態を読取表示。独自 math、新 backend、synthetic 計測成功の保存は行わない。Bridge 計測 GUI は具体的 backend 依存を残して保留 |

根拠：Bridge [config validation](https://github.com/MonakaVR/MonakaBridge/blob/42ec6c809c085a44cdb0d331f2bab452c79701fd/src/mapping/config.cpp)、[CLI](https://github.com/MonakaVR/MonakaBridge/blob/42ec6c809c085a44cdb0d331f2bab452c79701fd/tools/bridge_config.cpp)、[health](https://github.com/MonakaVR/MonakaBridge/blob/42ec6c809c085a44cdb0d331f2bab452c79701fd/src/routing/health.cpp)、[calibrator](https://github.com/MonakaVR/MonakaBridge/blob/42ec6c809c085a44cdb0d331f2bab452c79701fd/tools/steamvr_calibrator.cpp)。Desktop の [TrackerSettings](../../gui/src/components/tracker/TrackerSettings.tsx)、[AutoBone hook](../../gui/src/hooks/autobone.ts)、[Bridge panel](../../gui/src/components/settings/pages/MonakaBridgeSettings.tsx)。

関連 PICO `ui/Control.Core/Control.cs` は serial/connection/tracking/nullable validity/age、receiver heartbeat と control/output 状態を分離し、既存 pipe の `set_route` / `calibrate_translation` / `cancel_operation` を持つ。VIVE `ui/Control.Core/Control.cs` と `docs/control-ui.md` は setup/tracking owner、RF と stable identity を区別する。これらは source audit のみで、別 owner の API・保存先を Bridge policy と統合していない。両 repository の full suite/HIL は今回再実行していない。

## 選択した並行実装範囲と設計

既存 Bridge の config/validate/diagnostic だけで完結する **output policy + fail-closed health + tray の単一導線** を選んだ。P1 Normalizer、ingress/Core conversion、fixed Core/JAR、Fusion/recovery、wire、Admission Gate、frozen package の変更は不要で、software-only 検証ができるため。

| ファイル境界 | 責務 |
| --- | --- |
| `gui/electron/main/monaka-bridge-service.ts` | bounded/strict UTF-8 file read、既存 native validator、保存 transaction、cancel/dispose。IO の所有者 |
| `gui/electron/main/monaka-bridge-ipc.ts` | 既存 Desktop 内だけの private IPC。window owner、request/policy/ID 検証、dialog 再入防止 |
| `gui/electron/bridge-health.ts` | 時刻・publisher/config 一致・鮮度の presentation。backend の稼働を推測しない |
| `gui/src/bridge/controller.ts` | UI state、review、single-flight、取消・失敗・再試行・終了、遅延応答排除 |
| `gui/src/components/settings/pages/MonakaBridgeSettings.tsx` | 既存 React/Button/style を使う表示と操作 binding |
| 既存 main/preload/shared/App/settings | 同じ window の `/bridge` route、設定 store、native file dialog、tray navigation を接続 |
| `gui/tests/**` と `bridge-*` test configs | 明示的 software fixture と実装に対するテスト。製品 input からは参照しない |

React の既存 architecture を維持した。WPF/code-behind は新規作成せず、React component に domain/IO を置いていない。Core 接続が途切れていても Bridge route を表示できる。通常 Electron 起動時の既存 Core 起動・終了処理は変更していない。

保存はロード時の全文 bytes/token を保持し、候補を既存 CLI で validate、backup、commit 直前に再比較して rename する。変更は policy/revision のみで、P2R の nested runtime_contract を含む他の JSON 設定値を維持する。JSON の空白・整形は正規化され得る。取消が commit 前なら保存しない。commit 後なら成功を報告し、古い backup で別 writer の値を巻き戻さない。

同じ新 Desktop 同士は exclusive lock を共有する。旧 Bridge tray/CLI はこの lock に参加しない。全文再比較で検出できる競合は拒否するが、**最終比較と rename の間に外部 writer が書く race を完全には防げない**。旧 tray を read-only に変えるには凍結 Bridge の変更が必要なので今回行っていない。同じ設定を複数 editor から同時操作しないことが運用条件。新 Desktop tray 自体は書き込まず、同じ画面へ移動するだけである。

## 旧 GUI テスト意図の移植

未採用 WPF 案の 55 + tray 22 assertions を消して件数を小さくしたものではない。旧ソース・テストは当該 commit に保存し、Bridge baseline の既存テストも無変更。言語・UI owner が変わったため raw assertion 数の同一性は主張せず、次の挙動へ対応付けた。

| 元の検証意図 | 今回の実行箇所 |
| --- | --- |
| saved 復元、review 必須、選択変更で review 解除 | controller `review/draft/restoration`、renderer 操作 smoke |
| await 前の duplicate guard、保存中編集禁止 | controller 1000 回クリック、service duplicate、renderer 同一 turn 50 click |
| 取消完了待ち、取消で選択復元、失敗と retry、commit 後 cancel は saved | controller/service の cancel・failure・post-commit tests、renderer cancel/failure/retry |
| close/dispose 後の遅延更新排除、取消要求、load failure/cancel/retry | controller/service load/dispose tests、React StrictMode mount/cleanup と画面終了 |
| native validation、policy/revision だけ変更、backup/restore/no-op | service actual native CLI、実 Electron/preload/IPC 保存 smoke |
| 同 revision 外部 bytes、検証中変更、second writer、cancel candidate cleanup、overflow | service conflict/lock/validation/cancel/revision tests |
| native pose と backend/publisher を区別、pose 経時 aging、stale policy unknown | health/controller 時刻 tests、renderer validity/unknown 表示 smoke |
| 切断・再接続、wrong publisher、old/future/missing timestamp | health/controller + renderer disconnect/reconnect/old/future |
| UI review/save/cancel binding、identity 選択保持、historical 行、描画 | 実 React controls と screenshots。Electron native IPC も別 smoke |
| tray saved/observed/revision の差、status 読み込み失敗・再試行・bounded polling | 共通 panel/controller/health の tests。独立 tray status VM を作らず同じ panel へ集約 |
| tray は config を変更しない、単一 reviewed workflow を開く、Applied/offline を推測しない | pure tray unit、実 Electron Tray/MenuItem invocation、navigation 前後の config bytes 比較 |
| missing/malformed health、file mtime で鮮度を作らない | service actual file read + snapshot timestamp tests |

追加で path preference 保存失敗、stale token、未知 profile、承認フラグの独立性、古い validator の P2R extension 見逃し、native IPC owner/shape/dialog のテストを持つ。

## 最終差分の実測検証

Node `22.17.0` / pnpm `10.33.0`、locked SolarXR `00c38a6dc28070b30850a89c26b17928e56245d4`、追加 dev-only Playwright `1.64.1`、既存 lock の Electron `40.9.1`。依存は frozen install / ignore-scripts を基本とし、Playwright 追加分だけ lock を更新した。Electron は既存 cache の同版を task-owned build へ展開し、製品 main は起動していない。

| 実行 | 結果・証跡 |
| --- | --- |
| SolarXR submodule TypeScript build | PASS：[log](evidence/solarxr-build.log) |
| `pnpm --dir gui test` | **26/26 PASS、skip 0、388.59 ms**：[TAP](evidence/tests-final.log) |
| `pnpm --dir gui lint`（全体 tsc + eslint max-warnings=0 + prettier） | PASS：[log](evidence/lint-final.log) |
| `pnpm --dir gui build`（main/preload/renderer） | PASS：[log](evidence/build-final.log)、[exit codes](evidence/gui-check-exits.json) |
| `check:bridge:smoke`（test entry/config も tsc + prettier） | PASS：[log](evidence/smoke-typecheck.log) |
| software-only Electron main build | PASS：[log](evidence/native-fixture-build.log)。renderer/preload config missing の警告は test main だけを build するため。製品 preload は上記製品 build の成果物を使用 |
| Edge renderer 2件 + isolated Electron 1件 | **3/3 PASS、19.7 s、skip 0**：[log](evidence/browser-final.log)、[Playwright JSON](evidence/browser-result.json) |
| 凍結 Bridge `5fbf2a9` native configure/build + 変更しない全 CTest | **49/49 PASS、43.60 s、skip 0**：[result](evidence/bridge-result.json)、[JUnit](evidence/bridge-suite.xml)、[log](evidence/bridge-ctest.log) |
| 指定 P2R `42ec6c8` の native config validator 単独 build | PASS：[configure](evidence/p2r-validator-configure.log)、[build](evidence/p2r-validator-build.log)。runtime/backend は起動せず |
| `git diff --check` と source boundary audit | PASS：[audit](evidence/compatibility-audit.json)、[収集 script](evidence/collect_audit.py) |

製品 build の Sentry auth token 未設定警告は release/source-map upload を行っていないことを示す。build は exit 0。初回 JSX・test selector・smoke config の型・native fixture build 設定・test 環境変数の問題は修正し、最終結果を上記に保存した。失敗 run を最終 PASS の根拠には使っていない。

Bridge full suite は旧 helper が期待する Gradle 配置を **新しい task-owned 検証 clone の中** に用意して実行した。既存 Gradle へ渡す relay 以外は test commands、assertions、allowlist、scope inventory、gate、CMake を変更していない。元 checkout も branch 切替していない。既存 shared build output は使用していない。

### GUI smoke の範囲

実 React renderer で review/連打/取消/失敗/再試行/復元、tracking と component validity、identity 選択、old/future 診断、disconnect/reconnect を操作した。isolated Electron は実 preload/private IPC/service/native validator を通した保存・backup、実 Tray に設定した実 MenuItem の programmatic click、同 window の `#/bridge` 遷移、変更なしを確認した。スクリーンショットを目視確認済み。

- [current 診断・identity 選択](evidence/desktop-controls-current.png)
- [切断後の historical 診断](evidence/desktop-controls-historical.png)
- [取消・失敗・retry 後の保存状態](evidence/desktop-controls-saved.png)
- [実 Electron IPC 保存と tray 導線](evidence/desktop-native-ipc-tray.png)

**NOT RUN**：OS tray を手動マウス操作する試験、native file picker の手操作、packaged app の通常起動、接続済み実機、ADB/BLE/USB、SteamVR/VRChat 実出力、HMD/実計測。computer-use tool は Windows platform 初期化に失敗したため、専用 Playwright/Electron fixture で可能な画面検証を実施した。これを手動 OS/HIL PASS には読み替えない。再現手順は [GUI test README](../../gui/tests/README.bridge.md)。既存ポートを所有するプロセスの停止は行っていない。

## 指定 P2R/Core との read-only 適合性

| 確認対象 | 結果 |
| --- | --- |
| Bridge P2R `42ec6c809c085a44cdb0d331f2bab452c79701fd` | 独立 audit clone、source clean。旧 baseline 比で公開 CLI source と config example は同一。optional per-mapping `runtime_contract` と health の `capability_normalization` が追加される |
| P2R 設定の保持・検証 | matching native validator で nested contract を保持し policy/revision だけ変更。invalid contract は native が拒否。旧 validator は extension を無視するため、候補検証後に一時 invalid-contract probe を既存 validate CLI へ渡し、対応しない validator では保存拒否。timeout/launch/cancel は「対応」と数えない。Normalizer の実装・math は追加していない |
| health extension | extra field を無視する additive consumer。正常値・liveness・output delivery として捏造しない |
| Core ingress `ebdde9188b6d1a91befa22d499f3e4228d25a47b` | prepared base は祖先。base→指定 Core commit の `gui/`、root package/lock、SolarXR submodule 差分 **0**。既存 frontend/API 基準に version skew は確認されない |
| fixed Core/protocol/JAR/Normalizer/ingress/Fusion/recovery/wire | 新 GUI branch の protected runtime 差分 **0**。protocol submodule も固定 SHA を維持。Core/runtime の再実装・candidate への GUI 混入なし |
| Bridge protected surface | 元 Bridge の全 tracked regular file **972 files** を最終 49/49 clone と SHA256 byte 比較し差分 **0**。8 historical scope gates は製品 Bridge を無変更にすることで維持。例外・除外で PASS にしていない |
| 元の関連4 checkout | Bridge `5fbf2a9`、Protocol `0b4a27c`、PICO `deb455f`、VIVE `2cfa10f`。全 clean、元 branch/HEAD 維持。full SHA は audit JSON |
| 実 runtime 組合せの稼働 | **NOT RUN**。上記は consumer 契約と commit 境界・software 検証。P2R/4PFR runtime 本体や新組合せ全体の合格は主張しない |

旧 4PF の2 receipt 原本欠落は **旧 integrity FAIL のまま**。今回の GUI/validator/49 CTest 証跡で補完・再構成したことにしない。新 P2R / 4PFR の本線証跡とは区別し、その受入れ判定は本線 owner に残す。

## 残件と統合準備判定

この branch は **software-only GUI のローカル review 用として準備済み**。runtime 本線 `01a13e76-4fb2-76fa-ae47-cd1b9c9a001f` の作業 branch、integration branch、build output へ変更は入れていない。

残件は (1) 親が確認中の merge 対象/権限、(2) 旧 tray/CLI を含む全 writer の transaction 境界、(3) body/Main-Fallback の owner/API/固定 protocol handoff、(4) calibration の structured measurement/progress/cancel/result/apply 契約と実機計測、(5) 手動 OS/packaged/HIL 検証、(6) 旧 4PF receipt 原本欠落の解消である。

後で公開する対象は **MonakaVR/MonakaVR の `codex/gui-bridge-controls-20261015` の GUI 実装と report/evidence commits のみ**。未採用 MonakaBridge GUI proposal、Bridge/Protocol/PICO/VIVE checkout、runtime candidate は含めない。merge/push は今回実行していない。
