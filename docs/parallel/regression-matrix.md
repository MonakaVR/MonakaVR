# 並行回帰テストとローカライズの検証台帳

## D1 — 実装前監査（2026-10-21 JST）

基準は取得済み `origin/dev` の `a07d0c7b3c69edc61dfee5f0cae1e4756ea02589`。
作業ブランチは `test/monaka-parallel-regression-i18n`。以下は新規テストを追加する前の監査であり、既存テストを新規成果には数えない。
パスの略記 `core` は `server/core/src/test/java/dev/monaka/tracking/`。

| 既存テスト／製品境界 | 実際の検証内容 | 限界・未検証領域 |
|---|---|---|
| core/StaticInputSelectionTests.kt、製品 TrackerBodyAssignments.kt / MonakaInputConfigurationApi.kt | state先行catalog、静的能力と動的validity、Q/6DoF独立選択、publisher別identity、再接続、generation/外部writer/lock、未知拡張の保存、RPC queue | モードのsaved/active分離・GUIモード操作は未実装。未宣言能力は選択不可 |
| core/ArchitectureRevisionTests.kt、core/revision/MainFallback*Tests.kt、ConstraintResolverTests.kt | publisher・兄弟sourceの障害分離、session lease、Main FULLのPos+Q所有、明示fallback、較正保持 | 実機の認証・通信品質は検証しない |
| core/MonakaRuntimeTests.kt、ConstraintPipelineTests.kt | 固定codec、age、重複・metadata・poll、session、pause backlog、float overflow、backend障害、Direct/solver/private自己入力排除、Slime/HMD保持、IK数値変化 | ソフトウェア内の入力・solver境界。SteamVR実出力の証明ではない |
| core/MonakaSolverCompositionTests.kt | tick単一所有、重複commit/rollback、例外時再試行拒否、pause/close、production構築時のcapture禁止 | production Position Correction は CONFIGURED_RAW_HMD_BLOCKED。internalの将来用session試験は本番enableを意味しない |
| core/OutputContinuityTests.kt、RecoveryAngularDynamicsTests.kt | 初期FULL、遮蔽、current/aligned IK、物理sampleによるdwell、補間、最短回転、再遮蔽、pause、旧frame拒否 | 合成姿勢・決定的clock。実時間レイテンシは未計測 |
| core/HybridFoundationTests.kt | 実HumanPoseManager/ConstraintIkWritebackとMTP、Direct/Hybrid中の背景IK更新、calibrationとcomputed/visible identity保持、設定互換 | OS/SteamVRのrole登録・serial送信を実行していない |
| core/AssignedImuSampleFreshnessTests.kt、HipRotationCorrectionTests.kt | 物理sample age、未来・不明provenance、sequence/timestamp rollback、独立Main/IMU更新、epoch変更、learning/application分離 | 現行のソフトウェアprovenance判定。実機の認証成立を主張しない |
| core/PositionCorrectionSolverContinuityTests.kt、PositionCorrectionRuntimeOrchestratorTests.kt | typed入力、teacher/predictor lineage、Main復帰連続性、solver境界、拒否時の非更新 | **Position Correctionはproduction enableされていない**。internal fixtureと本番配線を区別 |
| gui/tests/bridge/input-selection.test.ts、gui/src/monaka-input/controller.ts / socket.ts | controllerの選択・cancel・競合・reload。socketは既存WebSocket上の別namespace、response shape・切断・timeout処理 | controller試験のAPIはfake。実socket/Core/GUI画面操作のE2Eではない。モード保存UIは後段 |
| gui/package.json、gui/src/i18n/config.tsx | lint=tsc/eslint/prettier、test=Node bridge tests、build=electron-vite。FluentResource/Bundle、選択locale→en順のReactLocalization fallback | lintはpublic/*.ftlを解析しない。既存bridge試験にFluent parse/fallback試験なし。新設専用テストで補う |

## D2 — 新設suiteと統合後の課題

`core/ParallelRegressionTests.kt` を独立suiteとして追加。製品codec・runtime・設定writer・solver・continuityを直接呼び出し、既存テストクラスは呼び出さない。9メソッド、モード3通りのパラメータ展開を含め11ケース。

| ケース | 基準devでの検証 | 後段／HIL |
|---|---|---|
| Slime Q / MVIP Pos+Q / MVIP Q / Unknown | 能力による選択とfail closed | GUI接続はB/C |
| 同名publisher／再接続 | 別identity、割当と保存内容保持、旧session拒否 | 実機再接続 NOT RUN |
| FULL→ROTATION_ONLY→NONE→FULL | 能力と割当不変、validityと所有者変更 | 実機遮蔽 NOT RUN |
| Main FULL／IMU | Main Pos+Qペア、独立IMUのfallbackと学習 | 物理認証 NOT RUN |
| 不明provenance／停滞timestamp | 学習停止と既存correction非更新 | 実機drift NOT RUN |
| IK/Direct/Hybrid | 実solverへの反映、自己入力排除、製品tracker identity保持 | SteamVR role/serialの実登録 NOT RUN |
| 初期FULL／再取得 | 既存dwellと中間姿勢、同一出力object | 実時間の追従品質 NOT RUN |
| 保存／競合／再読込／再構築 | source保存、lockと外部writer、既存設定保持 | B統合後にsaved/active mode差・mode保存restart gateを追加。未実装APIを仮定しない |
| pause/close／再構築 | 旧pose排除、同tick重複write拒否、epoch再学習 | OS二重Hook/プロセス再起動はdesktop既存suiteとHILを区別 |

## D3 — Fluent

en/jaへ11キーを追加。能力は静的宣言、validityは現在値として区別し、production補正・較正・SteamVR成功の断定文は追加しない。locale優先順とfallback実装は変更しない。

`gui/tests/bridge/parallel-localization.test.ts` の2ケースを既存Node runnerへ追加した。実際のFluentResource/BundleとReactLocalization.getStringを使用し、en/jaの完全な文字列・各キー1回・fr→en fallback・ja優先・override欠落時en fallbackを検証する。NodeではDOM overlayを使わず、公式constructorのparseMarkup=nullを使用する。画面のDOM検証ではない。

基準devのenには `settings-general-interface-dev_mode` / `-description` / `-label` の重複が各1件存在する（`git show a07d0c7b:gui/public/i18n/en/translation.ftl` で確認）。本作業で追加したキーとの衝突はない。scope外の既存文言は変更せず、全resourceのaddResourceエラーがこの固定3件だけであること、新設Monakaブロックのエラーが0であることを別々にassertする。ja/frのaddResourceエラーは0。FluentResourceが不正messageを読み飛ばしても、11キーの存在とformat結果の照合で検出する。

## 検証結果

環境: Windows / Microsoft OpenJDK 17.0.20.1 / Gradle wrapper 8.14.4 / Node 22.17.0 / pnpm 10.33.0。`pnpm --dir gui install --frozen-lockfile` 実行済み、lock変更なし。submoduleはコミットで指定されたrevisionを使用。プロトコルv2.1 artifact/manifest/lock/content検証はPASS。通常sandboxでwrapperの既存cache lockにアクセスできなかったため、権限付き実行で検証した。CI設定・skip・timeout閾値は変更していない。

| 時点／コマンド | 結果 | 証跡・意味 |
|---|---|---|
| baseline `gradlew.bat :server:core:test :server:desktop:test --console=plain` | PASS | evidence/baseline-jvm.log、baseline-counts.json。Core 1,940 / Desktop 153、failure/skipとも0 |
| baseline `pnpm --dir gui lint` / `build` | PASS | baseline-gui-lint.log、baseline-gui-build.log |
| baseline `pnpm --dir gui test` | FAIL | baseline-gui-test.log。34中22 PASS / 12 FAIL / skip 0。全12件がnative validator環境変数未設定で失敗 |
| 新設suite初回 `gradlew.bat :server:core:test --tests dev.monaka.tracking.ParallelRegressionTests --console=plain` | PASS | new-suite.log。初回9ケース、その後IMU物理ageと既存Direct restart gateを追加 |
| 最終 `gradlew.bat :server:core:test :server:desktop:test --console=plain` | PASS | final-jvm-tests.log、final-counts.json、parallel-suite.xml。Core 1,951 / Desktop 153、failure/skipとも0。新設suiteは11ケース、skip 0でJUnitに取り込まれた |
| `gradlew.bat :server:core:test :server:desktop:test :server:desktop:shadowJar :server:desktop:mtpProcessE2E --console=plain` | PASS | final-jvm.log、mtp-process-e2e.json。別publisherプロセスからUDP→実runtime/IK→captured Protobuf、feature off、再接続、port解放など11段階 |
| 最終 `pnpm --dir gui lint` / `build` | PASS | final-gui-lint.log、final-gui-build.log。ビルド成果物の実行・HILは含まない |
| 最終 `pnpm --dir gui test` | FAIL | final-gui-test.log。36中24 PASS / 12 FAIL / skip 0。baselineと同じ12ケースが同じ前提不足。追加2ケースはPASS |
| `cd gui; node --test --test-reporter=tap build/bridge-tests/tests/bridge/parallel-localization.test.js`（既存testによるtsc後） | PASS | localization-test.log。2 PASS / failure/skip 0 |
| GUI native validator統合試験の成立 | BLOCKED | `MONAKA_BRIDGE_TEST_VALIDATOR`（固定baseline）、`MONAKA_BRIDGE_TEST_LEGACY_VALIDATOR`、`MONAKA_BRIDGE_TEST_P2R_VALIDATOR`の適切な実artifact指定が必要。無関係なローカルexeを固定版と推定せず、fake PASSやskipへ置換しない |
| B統合後のsaved/active mode差・mode保存restart gate / Cの診断UI接続 | NOT RUN | 基準devにAPIなし。既存Direct source編集restart gateは新設suiteで検証済み |

最終JVM再実行前に追加したIMU fixtureで、copy時にpositionをnullにして品質をTRACKEDのまま残す誤りを検出した。fixtureの品質もUNAVAILABLEへ切り替えて製品value boundaryの契約に合わせた。製品コード・既存test・期待値・閾値は変更していない。

新設suiteの主要メソッド: `capabilityChoicesFailClosedAndValidityNeverRewritesSavedSelection`（能力/validity/保存）、`publisherIdentitySurvivesReconnectAndCannotOverwriteSibling`（identity/session）、`allModesKeepSolverAndOutputIdentityAcrossMainLoss`（全モード/実solver/重複commit/feedback）、`hybridDwellRequiresNewPhysicalFullAndBlendsBeforeSettling`（既存default dwell/補間）、`unauthenticatedOrStationaryImuCannotAdvanceLearningAndEpochChangeRevokesIt`（学習/epoch）、`independentImuPhysicalAgeControlsFallbackWithoutSplittingFullMainPair`（Mainペア/物理age）、`writerConflictLockAndReloadPreserveSourcesAndUnknownFields`（writer/再構築）、`currentDirectAssignmentRequiresRestartBeforeSourceEdit`（既存gate）、`pauseCloseAndReconstructionNeverReviveQueuedPose`（lifecycle）。

製品tracker object/id/name/body roleの継続性はソフトウェア上で検証し、SteamVR role/serialの実登録はNOT RUN。OS hookの重複登録は新設suiteの検証対象外（solver同tick重複commit拒否と区別）。新設suiteの外部入力は固定kitのcodecを使い、製品メソッドの複製・補間の期待式の複製・mock-only成功判定は行わない。

実機・HMD・SteamVR・PICO・VIVE・OS tray操作はすべて NOT RUN。

evidence/sha256.jsonは各証跡のGit blobバイト列のSHA-256を記録する。Windows checkoutのCRLF変換後のファイルバイトとは区別する。ログにはツールが出力した末尾空白を保持しており、diffの空白検査はコード・翻訳・本台帳を対象としてPASSを確認した。


## 公開検証ログの匿名化（2026-10-21）

公開証跡のうち11件のログに含まれていたWindows/macOS/Linux形式のユーザー固有ホームディレクトリ名を `REDACTED` に置換した。ビルド結果・試験件数・診断情報の意味は変更していない。置換後のGit blob（LF）のSHA-256を `evidence/sha256.json` に再記録した。これは保存済みログの公開向け後処理であり、当時のCIを再実行したことを意味しない。

**履歴上の制約：** Git履歴の書き換えは行っていないため、以前のコミット上の生ログは引き続き取得可能である。現行ブランチHEADの匿名化と、公開履歴からの恒久削除は別作業として扱う。
