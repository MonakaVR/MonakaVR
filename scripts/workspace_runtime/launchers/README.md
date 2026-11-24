# 製品の通常起動

このフォルダーはMonakaVRソースの `scripts/workspace_runtime/launchers` から明示配置する。
各入口はスクリプト位置を基準に、対応repoの固定 `Build/runtime` を起動する。

| 入口 | 起動対象 |
|---|---|
| `Start_PicoMotionTrackerBridge.cmd` | `PicoMotionTrackerBridge/Build/runtime` のReceiver trayと、その所有service |
| `Start_ViveUltimateTrackerBridge.cmd` | アルトラ側の起動スクリプトを経由して `ViveUltimateTrackerBridge/Build/runtime` のトレイと、その所有サービスを起動 |
| `Start_MonakaBridge.cmd` | `MonakaBridge/Build/runtime` のnative owner。GUIを所有情報付きで開く |
| `Start_MonakaVR.cmd` | `MonakaVR/Build/runtime/win-unpacked` のdesktop GUIと、そのGUIが所有するJava server |

通常起動はBuild、install、driver登録を行わない。`runtime/stable`、日付package、別worktreeへfallbackしない。
`--check` は起動に必要なファイルと引数を確認するだけで、製品を起動しない。source包含・機能受入を証明する操作ではない。
通常起動にcatalog選定・promotion・全binary再hashは不要。PICOの既存ユーザー設定とBridgeの既存 `config/bridge.json` を使用する。

MonakaVRは `--profile <絶対directory>`、`--monaka-mtp`、`--server-port <port>` を受け付ける。
profileはvrconfigファイルではなく、その配置directoryを指定する。存在しないdirectoryはGUIが作成できる。
Javaは `MONAKA_JAVA`、`JAVA_HOME`、PATHの順で見つけ、GUIの子環境へ `JAVA_HOME` を渡す。
EXE／JAR直接起動や別Java環境を通常入口として追加しない。

必要変更をdevへ統合・commitし、sourceと既存成果物の対応を先に判断する。
内容とbuild/dependency入力が同一なら既存検収結果と成果物を再利用し、SHAが変わっただけで再Buildしない。
Buildが必要な製品だけ、そのrepoのビルド入口を明示実行する。アルトラの入口は `Build_Runtime.cmd`、ほかの3製品は `python -B scripts/build_runtime.py`。
MonakaVRの `--offline` は既存の固定依存物を使う場合に指定する。
Pythonのビルド入口では、前後のsource一致と `Build/provenance/<channel>.json` を記録する。通常起動はその記録からsourceを逆選定しない。
