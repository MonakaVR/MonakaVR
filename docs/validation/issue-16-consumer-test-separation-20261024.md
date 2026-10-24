# Issue16前段: Consumer一般試験の分離検証 — 2026-10-24

## 対象と変更範囲

Base: `dev@301ea5d34649bbcab71e26ad00932fe99c2f6449`
Branch: `test/issue-16-consumer-test-separation`

Bridge固有試験を将来整理する際に、Consumer一般の入力選択7件・Fluent回帰2件を巻き込まないため、次の2ファイルを同じ相対深度へ移動した。本文、import、fixture、assertion、コメント、試験名は変更していない。

- `gui/tests/bridge/input-selection.test.ts` → `gui/tests/monaka-input/input-selection.test.ts`
- `gui/tests/bridge/parallel-localization.test.ts` → `gui/tests/localization/parallel-localization.test.ts`

設定変更は `gui/bridge-tests.tsconfig.json` と `gui/package.json` のみ。前者は従来のBridge includeを維持して2ディレクトリを追加した。compilerOptions/rootDir/outDirは不変。後者は既存test:bridgeを不変とし、Consumer単独test:consumerとBridge+Consumerを両方発見する標準testを定義した。依存バージョン、lockfile、CI、skip、実行時製品コードは変更していない。

本書は当該作業専用の検証文書。commit SHAおよびDraft PR URLは最終作業報告とPRのHEADを参照する。

## ソース不変性

| 対象 | before SHA256 | after SHA256 | ケース数 / Git blob |
|---|---|---|---|
| input-selection | `8d45c54f47188aa28f4996064110a306c5655abf250ca59fcd9b357d10365056` | `8d45c54f47188aa28f4996064110a306c5655abf250ca59fcd9b357d10365056` | 7 / `5f594ce3ef1c4259028da329b57d8444bfb38b7e` |
| parallel-localization | `a7ff2023c3ef0be6a101340a7912fd3917abe213d9af32da7beb2748a75fbcf6` | `a7ff2023c3ef0be6a101340a7912fd3917abe213d9af32da7beb2748a75fbcf6` | 2 / `d7b944eeeaad2ca168ac669ddf196fa7a1c5bf77` |

`input-selection source bytes: IDENTICAL`
`parallel-localization source bytes: IDENTICAL`

実ファイルの長さも各6286 / 4355 bytesで不変。既存Bridge固有のcontroller/ipc/serviceの3試験ソースもSHA256と試験名が不変。

| 設定ファイル | before SHA256 | after SHA256 |
|---|---|---|
| bridge-tests.tsconfig.json | `21cc39165b00cabb5d0e191ee5329c5fe7cae880e0c56b1f19bac18e6a06da2e` | `231b03974706faad70d45197b3c1518802891884e77e74c866949aed3a00425f` |
| package.json | `ff470ce631647e3a85049c91f47f97eb004699eb4fb12d54359fc264207d456a` | `a0e5468a0b5b9d8a90c318af26767a061c26d01b4b90ac2a2b82cf7c765d0cf0` |

## 実行環境と基準計測

Windows、Node v22.17.0、pnpm 10.33.0、TypeScript 5.9.3。同一lockfile blob `47c106e97cba319a6e7138a0a4be3dc01eed60a9` の既存GUI依存を読取入力として再利用し、新worktree内に依存リンクと所有build/cache出力を作成した。SolarXR sourceは固定gitlink `00c38a6dc28070b30850a89c26b17928e56245d4` / cleanを確認した。新しいProtocol kitやvalidatorを作成・偽造していない。

固定baseで変更前の標準pnpm testを実行し、Node TAP実測は36 tests / 24 pass / 12 fail / 0 skipだった。TypeScript ASTでも入力選択7、localization2、Bridge固有27、合計36件を確認した。移動後は所有build/bridge-testsだけをcleanして再コンパイルし、旧位置のstale JSによる重複発見を防いだ。

## 試験結果

すべてgui cwdで実行した。

| Gate / command | 発見数 | PASS | FAIL | SKIP | 判定 |
|---|---:|---:|---:|---:|---|
| Baseline pnpm test（移動前） | 36 | 24 | 12 | 0 | 既存validator前提不足 |
| A: pnpm exec tsc --project bridge-tests.tsconfig.json | — | — | — | — | PASS / exit 0 |
| B: pnpm test:consumer | 9 | 9 | 0 | 0 | PASS / exit 0 |
| C: pnpm test:bridge | 27 | 15 | 12 | 0 | BLOCKED BY EXISTING TEST PREREQUISITE / raw exit 1 |
| D: pnpm test（移動後） | 36 | 24 | 12 | 0 | BLOCKED BY EXISTING TEST PREREQUISITE / raw exit 1 |
| E: pnpm lint | — | — | — | — | PASS / exit 0 |
| E: pnpm build | — | — | — | — | sandbox内EPERM。元ログ保持、同一commandを所有出力へsandbox外で1回再実行しPASS / exit 0 |
| 対象4ファイルのpnpm exec prettier --check | — | — | — | — | PASS |
| F: diff scope / byte / count / discovery | 36 | — | — | — | PASS |

Bridge27 + Consumer9 = baseline36。移動前後の標準testは36件の試験名・個別PASS/FAIL結果が完全一致し、Consumer9件は標準aggregateにも含まれた。単純regexの件数だけを根拠にせず、実Node TAPとTypeScript AST・コンパイラのfile discovery・実compiled glob展開を照合した。

12 FAILは移動前後ともservice.test.tsの同一12ケースで、すべて同じ既存ガード
`Provide the pinned baseline validator path; no synthetic native PASS is substituted.`
で停止した。MONAKA_BRIDGE_TEST_VALIDATOR / MONAKA_BRIDGE_TEST_LEGACY_VALIDATOR / MONAKA_BRIDGE_TEST_P2R_VALIDATORは未設定。このnative validator受入はNOT RUN / prerequisite BLOCKEDであり、標準試験をPASSとは報告しない。無断skip、合成validator、件数削減、Consumerだけへの標準test置換は行っていない。

## 負例

実sourceやcommitへmutantを適用せず、所有audit領域のtemp copy/設定値で検証した。以下の4種類・5パターンをすべてgateが拒否した。

| 負例 | 検出結果 |
|---|---|
| 新しい2 includeを除きtests/bridgeだけへ戻す | TypeScript compiler file discoveryからConsumerが消え、compile gateが拒否 |
| 標準testから2 Consumer globを外す | 実compiled glob発見が36→27となり、aggregate gateが拒否 |
| input-selectionのtest statementを1件除去 | AST件数7→6で拒否 |
| localizationのtest statementを1件除去 | AST件数2→1で拒否 |
| input-selection末尾へLF 1 byte追加（件数7は維持） | source hash不一致でbyte-preservation gateが拒否 |

## 証跡

ローカル専用auditにbaseline/after AST catalog、Node TAP、全件の試験名・結果、負例結果を保存した。次は実測成果物のSHA256であり、過去PRの結果を今回のPASSへ換算していない。

| 成果物 | SHA256 |
|---|---|
| bridge.log | `202f67f0185e0ce19cd4a9eeccbaad2a32dad0ef3fe4b48294ababef54e9ea9b` |
| build.log | `c65c3ed9ab6eb6f05e9e62086bd6c141c2020b47c342f9fd56ab30ca1d3ecf02` |
| combined.log | `118cad3e697c988b9d8cf77340f92d92db1de3fd7972f3101ab7af0bfaa4aea4` |
| after-catalog.json | `b965e1e768b6dea20e398c2770de26f1ece2ba5c7eba0395fcdf4abfe6cf4cef` |
| lint.log | `313507138a7b24881a640f9a521df5d09b122680695828c8eb75c0ff45b228eb` |
| consumer.log | `3b27eff21bd1be9b682e624b4056a5fad6c91a563cfe88248d0cf828efb814cb` |
| baseline-standard.log | `14b425b07cdfc4dc7cb2eb0d1392606952bd4e489569f69c4959889f8856714d` |
| build-owned-retry.log | `b47798baf69ce6a615de0fb9c6949c49f232f9418ea6e8ab2cc7b235c7dffeaa` |
| baseline-catalog.json | `7f631bca7a3c726b11d71216f5f97125fea04a9874b23cc06e160ac8f73b15bb` |
| verification.json | `8431e0493c428abbbc79002a4319ca32769c3eea8b092404bb3df4cd3c81a106` |

## 維持する判定と残件

| 判定 | 状態 |
|---|---|
| S1-DIFF | PASS (inherited) |
| Existing S1-SOFTWARE | FAIL |
| PRODUCT-BOOT | NOT RUN |
| PRODUCER-INDEPENDENCE | NOT RUN |
| Architecture Release Gate | NOT SATISFIED |
| G2-OWNER | BLOCKED |
| Phase B / destructive Issue16 removal | NOT STARTED |
| Output-mode GUI | PENDING |
| Runtime/product source changes | 0 |

本作業では既存AGENTSのProducer独立性規範、CI・Spotless、既存試験ガードを変更していない。Bridge-private runtime/UI/config/IPC/preload/tray、一般入力widget/controller、Core、Fusion/IK、Protocol、出力、ユーザー設定、実機へ変更を加えていない。Owner G2の再判定や本体撤去、PR/Issueのmerge/close、branch削除は別指示待ち。PR18 merge後Full Build未観測の原因調査も実施していない。
