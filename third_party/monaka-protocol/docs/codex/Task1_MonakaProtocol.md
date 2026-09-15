# Codex Task 1: MonakaProtocol

単独投入用実装指示書。調査日：2026-09-14 UTC。対象repo以外のworkspaceは不要。

**契約状態：C1候補版。参照マスター本文は未取得。** 実装順序と明示handoffを守り、C1を後続Taskで独自変更しない。本ファイルに共通契約を全て含む。

## Repository

| 項目 | 値 |
|---|---|
| 対象repo | [MonakaVR/MonakaProtocol](https://github.com/MonakaVR/MonakaProtocol) |
| default branch設定 | main |
| base branch | `main` |
| 監査base SHA | `UNBORN: commit/refなし` |
| 作業branch | `refactor/monaka-layer-separation` |
| C1本文SHA256 | `3a76435c8b25b3975028f9a83c4dc3d1e3848806c9608d885f5bba74a1198ed3` |

## 作業手順（必須）

このTaskが変更するworkspaceは上記repoだけ。他repoへのcommit/PR作成をしない。必要な他repo由来code・interfaceは本文に明記したversion固定kit/bundleとして受け取る。外部workspaceがある前提にしない。

1. repositoryとworktree状態、AGENTS.mdを確認する。既存の未commit変更を上書き・削除しない。
2. `git fetch --no-prune origin`でremoteをfetchする。branch削除を伴うpruneは行わない。
3. 指定base branchのremote refを確認し、実際のbase HEADを記録する。下の監査SHAと違う場合は差分を確認し、変化と採用理由を報告する。fast-forwardで責務に矛盾がないことを確認できれば新HEADを使用し、無条件にmainへ戻さない。
4. 確認したbase HEADから`refactor/monaka-layer-separation`を作成する。通常は `git switch -c refactor/monaka-layer-separation <確認済BASE_SHA>`。既存の同名local/remote branchがある場合は作り直さず、指定baseとの祖先関係・既存作業を確認して継続する。遠隔にだけある場合は追跡branchとして取得する。
5. **この作業branch上だけで変更する。** 既存branch削除、force push、resetによる履歴差し替え、amend/rebase等のhistory rewriteは禁止。mainへの直接commit/mergeはしない。
6. 小さいreview可能なcommitに分ける。各段階のbuild、test、hardware未確認を分離し、本文のDoDに照らして完了状態を報告する。

### 未初期化repoの例外処理

調査時にはremote branchが0件だった。fetch後も`refs/heads/main`がない場合、base branch欄は`main (unborn)`、base SHAは`null / no commit`として記録する。`git switch --orphan refactor/monaka-layer-separation`でこの作業branchを作り、最初のcommitをここに置く。main refがあるようにSHAを捏造したり、mainへ先に仮commitをpushしたりしない。開始までにmainが初期化されていれば、その実HEADを確認して通常手順に切り替える。

## このrepoの責務

共通data model、schema、codec、version互換性、validation、fixture、配布kitの唯一の所有者。TrackerObservationとMTPを別型として実装する。新repoは2026-09-14調査時にbranch/commitが0件だった。default branch設定はmainだが、存在するmain refやbase SHAがあると扱わない。

## 非責務

PICO/VIVEアクセス、socket service、runtime起動、mapping/calibration計算、GUI、body role、Fusion/Fallback/IK、SteamVR driver。これらをProtocolへ追加しない。

## 実装の開始条件と進め方

1. この指示書内のC1を一つの原本として取り込む。マスター指示書が追加提供された場合は先に照合し、差分を`docs/design-decisions.md`に記す。未提供の場合はC1をcandidateとして実装できるが、「マスター照合済み」「正式仕様確定」と報告しない。
2. Protocol未初期化の分岐手順で作業branchを作る。もし開始までにmainが初期化されていたら、新しいtreeを読み、新base SHAを記録して今回のbootstrapが不要になったことを明記する。
3. `docs/C1.md`へC1全文を保存し、そのhashからschema・fixtureとの一貫性を検証できるようにする。型からtransportやvendor依存が入らないことをCIで確認する。
4. `schema/tracker-observation.schema.json`はTrackerObservation/ObservationDeviceStateのoneOf、`schema/monaka-tracking.schema.json`はMtpPose/MtpTrackerStateのoneOf。protocol/type定数で識別する。$schemaは2020-12。必須field、nullability、vec3/quatの配列長、数値範囲、ID制限を定義する。未知任意fieldをminor互換として許す。
5. 文字列U63上限、UTF-8 byte length、duplicate key、finite、quaternion norm、component validityとtracking_stateの整合性など、schemaだけでは足りない制約を共通validatorで補う。decoderは全検証前にoutを書き換えない。
6. C++17 codecとJVM17 codecを実装し、C1.6のpublic API名を固定する。C++は既存の信頼できるJSON parserをrevision/hash固定で使い、手書きの不完全なJSON parserを増やさない。JVMはKotlinモデルを提供し、必要なserialization dependencyをexact versionで宣言する。新repo側の言語toolchain版は選定結果をlockに記す。
7. fixtureを正常/異常に分け、各fixtureに期待decoded semantic modelまたはerror.codeを添える。unknown battery、orientation-only、invalid positionが動くpayload、q/-q、session変化、clock別epoch、2^53を超えるtimestampを含める。
8. 2つの言語間の相互codec testを実行する。JSON objectの順序をwire ABIとしない。encode出力は再現可能にするが、decode側はkey順序/空白に非依存とする。
9. `monaka-protocol-kit-v1.0.zip`を作る。C++ source/include/CMake、JVM JARとruntime dependency manifest、schema、fixtures、docs、NOTICE、SHA256SUMSを含める。後続taskは1repoだけでもkitからbuildできることをclean workspaceで確認する。未配布Maven package名の存在を仮定しない。
10. `protocol.lock.json`へschema_commit、wire major/minor、artifact SHA256、各schema/fixture/API packageのSHA256、source_tree_clean、toolchain/dependency版を記録する。zip自身のSHAをzip内部へ再帰的に埋め込まない。外部のhandoff manifestにzip SHAを置く。

## Compatibility

現存POTB v1、PICO receiver C ABI v1は別protocolとして存続する。C1 v1と番号が同じでも互換とはしない。既存PICO/VIVE型をそのままrenameして二つのwireへaliasしない。未来のbreaking changeではmajorを上げ、同major内でunit/座標/時刻の意味を変更しない。

## Testsとbuild

- Unit：schema validation、codec全error、整数境界、非有限値、UTF-8、duplicate key、zero quaternion、partial sample、optional derivative、capability整合性。
- Build：C++17のWindows/Linux、JVM17。Android ABIには依存しない。今回PICO PC shim方式なのでNDKへのJSON依存追加は不要。
- Cross language/mock：fixture双方向、session/sequenceを使うconsumer用fixture、部分loss、stale replayを作る。Protocol自身はclock syncや状態cacheを実装しない。
- Hardware：不要。実機精度やトラッカー接続をこのTaskのPASSに含めない。
- Performance：代表4096byte以下のframeでencode/decode CPU時間・alloc・payload量を測定し条件付き数値を記録。根拠のない120Hz保証はしない。

## Definition of Done

二つの別schema/modelと4messageがC1どおり存在し、C++↔JVM testがPASS。別workspaceからkitを利用してbuild可能。socket/vendor/OpenVR/solver依存が0。kitとmanifestのhashが一致。C1、API、schema、fixture、依存版の手渡しに欠落がない。

マスター未提供時のDoD表記は`candidate implementation complete / master reconciliation pending`。後続taskへ実際に渡す契約はこの1組だけとし、マスター照合で変える場合は全指示書を同期更新する。


<!-- BEGIN COMMON C1 -->
## 共通契約 C1 — 全Taskで同一の定義を使用

**状態：Work提案 C1 / wire候補 1.0。公開済みprotocolではない。** 添付で参照された「MonakaVR レイヤ分離・共通プロトコル化 実装指示書」は未取得。Task 1でその本文が渡された場合は最初に照合する。矛盾があればこの契約を一度だけ修正し、Task 2～5も同じ内容へ再生成する。後続Taskが独自のschema/APIを追加・変更することは禁止する。

### C1.1 設計上の選択

- `TrackerObservation`はBackendの観測。`MtpPose`はBridgeでID mapping、座標変換、calibrationを適用した入力。別schema・別型とする。
- 今回の共通境界はPC上のloopback UDP。wireはUTF-8 JSON（RFC 8259）、schemaはJSON Schema Draft 2020-12。NDKを含むPICO内部のHMD↔PC POTB転送は変更せず、PC Backendから共通観測を出す。このPOTB経路はPICO Backend内の実装詳細であり、MonakaBridge/MonakaVRには公開しない。
- JSON選択はマスター未提示部分に対する本案の判断。既存C++/Kotlinの接続を型・byte order・ABIから独立させ、mockで検証しやすくするため。sensor取得周期の向上は目的に含めない。JSON化のCPU/alloc/latencyは実測し、将来binary codecが必要ならMonakaProtocolで一括追加する。
- `sizeof(struct)`、`#pragma pack`、JNA carrierのメモリ画像をwireとしない。既存C ABIは移行期間中の旧consumer専用であり、C1 ABIではない。

### C1.2 正規の型定義

以下はschema記述用の型表記。`?`のないfieldは必須。`null`許可とfield欠落は別物。任意fieldの欠落は`null`と同義にdecodeする。これを唯一の定義としてTask 1でJSON SchemaとC++/JVMモデルを作る。

```typescript
// U63 = decimal string matching 0|[1-9][0-9]*, 0..9223372036854775807.
// Double = finite IEEE754 binary64 JSON number; NaN/Infinity are prohibited.
// Id = nonempty UTF-8 string, 1..96 bytes, no NUL/control character.
// Uuid = lowercase canonical UUID string, 36 ASCII characters.
type U63 = string;
type Id = string;
type Uuid = string;
type Vec3 = [number, number, number];
type QuatXyzw = [number, number, number, number];
type TrackingState = "initializing" | "tracked" | "degraded" |
                     "lost" | "disconnected" | "unknown";
type Capability = "position" | "orientation" | "linear_velocity" |
                  "angular_velocity" | "linear_acceleration" |
                  "battery_fraction" | "charging";
type CoordinateSpace = {
  id: Id;                 // shared origin/map identity, not merely axis names
  convention: Id;         // profile key; no automatic identity fallback
  revision: number;       // uint32; changes when origin/map changes
};
type Battery = {
  fraction: number | null; // 0..1; unknown is null, never a vendor raw bucket
  charging: boolean | null;
  timestamp_ns: U63;      // same clock as this envelope; actual metadata receipt
};
type Derivative = {
  value: Vec3;
  frame: "space" | "device";
  evidence: "measured" | "derived";
};
type Header = {
  version: { major: 1; minor: number }; // uint16; v1.0 encoder emits minor=0
  source_id: Id;
  session_id: Uuid;        // new for each publisher lifetime
  clock_id: Uuid;          // identifies monotonic epoch, may equal session_id
  sequence: U63;
  timestamp_ns: U63;       // immutable sample/state admission time in publisher clock
  sent_at_ns: U63;         // encoding time in the SAME clock
  timestamp_kind: "sample" | "receive";
};
type TrackerObservation = Header & {
  protocol: "monaka.observation";
  type: "pose";
  device_id: Id;
  position: Vec3 | null;             // metres in declared coordinate space
  orientation: QuatXyzw | null;      // device-to-space orientation convention
  validity: { position: boolean; orientation: boolean };
  orientation_evidence: "device" | "sample_sanity" | "none";
  linear_velocity?: Derivative | null;      // m/s
  angular_velocity?: Derivative | null;     // rad/s
  linear_acceleration?: Derivative | null;  // m/s^2; gravity removed
  tracking_state: TrackingState;
  battery: Battery | null;
  coordinate_space: CoordinateSpace;
  capabilities: Capability[];
};
type ObservationDeviceState = Header & {
  protocol: "monaka.observation";
  type: "device_state";
  device_id: Id;
  presence: "present" | "absent" | "unknown";
  tracking_state: TrackingState;
  battery: Battery | null;
  coordinate_space: CoordinateSpace;
  capabilities: Capability[];
};
type MtpPose = Header & {
  protocol: "monaka.tracking";
  type: "pose";
  tracker_id: Id;
  position: Vec3 | null;            // normalized/calibrated reference point
  orientation: QuatXyzw | null;     // normalized device-to-world rotation
  validity: { position: boolean; orientation: boolean };
  linear_velocity?: Vec3 | null;   // in normalized world axes, m/s
  angular_velocity?: Vec3 | null;  // in normalized world axes, rad/s
  linear_acceleration?: Vec3 | null; // in normalized world axes, m/s^2
  confidence: { position: number; orientation: number }; // each 0..1
  tracking_state: TrackingState;
  coordinate_space: CoordinateSpace;
  capabilities: Capability[];
  mapping_revision: number;        // uint32; ID/calibration/profile revision
  input: {
    device_id: Id;
    session_id: Uuid;
    sequence: U63;
  };
};
type MtpTrackerState = Header & {
  protocol: "monaka.tracking";
  type: "tracker_state";
  tracker_id: Id;
  presence: "present" | "absent" | "unknown";
  tracking_state: TrackingState;
  battery: Battery | null;
  coordinate_space: CoordinateSpace;
  capabilities: Capability[];
  mapping_revision: number;
};
type Envelope = TrackerObservation | ObservationDeviceState |
                MtpPose | MtpTrackerState;
```

### C1.3 追加の意味規則（JSON Schemaだけでは不足する）

1. position/orientationの`validity=true`には有限値の対応sampleが必要。falseの数値は診断用で、solver/SteamVRへの入力には使わない。有効component/非null derivativeには対応capabilityが必要だが、capabilityがあっても現在validとは限らない。capabilitiesは重複なし。
2. `orientation_evidence=sample_sanity`は数値上正常なQuaternionを使えることだけを示す。光学追跡成功やyawの絶対精度を保証しない。`validity.orientation=false`ではevidenceは`none`。有効Quaternionはnormが0.5..1.5に入り、Backendは元値を保持、Bridgeでnormalizeする。MTPの有効Quaternionは`abs(norm-1)<=1e-5`。qと-qは同じ回転。
3. unknown batteryはnull。既存PICO bucketを0..100%へ読み替えない。未確定の加速度・角速度の単位、frame、gravity処理はnullで表す。candidate/raw packet/slot/runtime IDを追加fieldで外へ漏らさない。
4. `tracking_state=disconnected/lost/initializing/unknown`のままpose componentを有効にしない。orientationだけ利用できる場合は`degraded`。`tracked`はpositionとorientationが利用可能。positionだけ有効な場合も`degraded`。`degraded`は少なくとも1 component有効、`tracked`は両方有効。DeviceStateの状態はpose freshnessを更新しない。
5. MTP confidenceは測定誤差の確率ではなく0..1の品質指標。無効componentは0。有効componentは>0。Bridge初期policyはpositionの既知valid flag=1.0、device evidence orientation=1.0、sample_sanity orientation=0.5。policy値は設定/版で管理し、ベンダー比較の精度保証にしない。Monakaの既存rankとの対応はTask 5で明示する。
6. IDはcase-sensitiveなopaque文字列。source_idはインストール単位の永続ID（同一PC上の別Backendを区別）、device_idはPICO full serial / VIVE full MAC等の永続ID。slot、列挙順、IP:portを永続IDにしない。Bridge mappingは`(source_id,device_id)->tracker_id`。同一source内の衝突はエラー。別sourceの同じtracker_idは別観測として保持する。
7. body role、SteamVR role、IK target、priority、fallback state、solver stateは両wireに入れない。身体への割当はMonakaVRの設定。Bridgeのmappingは物理deviceとlogical trackerの対応、および座標・装着transformまで。
8. MTPのconventionは必ず`rh_y_up_neg_z_forward`。右手系、+X右、+Y上、-Z前、SI、Hamilton quaternion xyzw。space.idは実際の原点を共有する設定ID。同じ軸規約でも別空間を融合しない。MonakaVRが期待するspace.id/revisionと不一致なら隔離する。
9. quaternionの交換順xyzwとライブラリのconstructor引数順を混同しない。ktmathへの生成は`Quaternion(w,x,y,z)`。OpenVR構造体への設定もw/x/y/zを名前で行う。

### C1.4 時刻・sequence・再接続

- wireのU63はJSON numberにせず10進文字列。float変換を経ずC++/JVM整数へparseする。source processで単調clockのepochを定義し、timestampとsent_atは同じclock_idで非負。wall clock禁止。
- `timestamp_ns <= sent_at_ns`。sample受領時にtimestampを1回だけ決定する。clock再同期・poll・heartbeat・battery更新・output切替でsampleのtimestampを作り直さない。
- 初期PC Backendはtimestamp_kind=`receive`を基本とする。PICOはPCで最初に受けたposeの時刻、VIVEはHID pose受信時刻。PICO query timestampやpoll成功をsensor新規sampleの証拠としない。HMD→PC前の真のsensor ageはこの方式では保証できない。
- 別processの単調clockを直接引かない。UDP受信側で`age=sent_at_ns-timestamp_ns`を整数演算し、初回受理時の自clockに`receivedAt-age`として写像して固定する。ローカルclock開始前になるsampleはstale扱い。これは送信前ageの保存であり、通信遅延を含む精密clock syncではない。loopback遅延は別計測する。
- 新protocolのclockはMonaka runtimeの注入可能な非負monotonic clockへ対応させる。JVM `nanoTime()`とWindows QPCのepoch一致を仮定しない。MonakaのSlime adapterとMTP adapterへ同じnowNanosを渡す。
- pose sequenceは`(protocol,type,source_id,device_id又はtracker_id,session_id)`ごとに単調増加。開始0可、U63上限前にsessionを更新。古い/重複sequenceはpose更新・freshness更新に使用しない。device_state/tracker_state sequenceはposeと別stream。
- Bridgeは新観測を受理した時だけMTP pose sequenceを増やす。重送は同じsequence/inputを保持し、sent_atだけ更新できる。calibration更新による再出力ではmapping_revisionとMTP sequenceを増やしてよいが、元sampleのageを保存する。
- session変更で該当sourceのcache・clock mapping・derivative履歴・sequenceを破棄。終了済sessionからの遅延packetへ戻らないよう、受理中/retired sessionを区別する。座標revision変更でも同様に古いtransformと履歴を破棄する。
- 初期timeoutはpose 500 ms（設定可）。battery/state受信でpose timeoutを解除しない。休止・unpair・明示absentはcomponentを即時無効化する。再接続で同じ永続IDの登録を増殖させない。
- optical lossと通信断は区別する。数値が静止しているだけではstale判定しない。PICOの「queryは続くが実体は切断」の既知制限は追加のliveness証拠なしには解決済としない。

### C1.5 wire・version・transport

- 1 UDP datagram = 1 JSON object、最大4096 bytes、最大JSON深度16。UTF-8不正、BOM、duplicate key、切断JSON、型不一致、範囲外整数、非有限数、余分なJSON objectは拒否する。field順序/空白には依存しない。
- Protocolのmajor非対応は拒否。同majorの新minorは既知message/必須fieldを検証し、未知の任意fieldを無視して受理可能。新しい必須field・既存意味/単位変更はmajor更新。未知message typeは隔離/計数し、正常な隣接datagramの処理を継続する。
- 未知enumは該当messageをUnsupportedValueとして破棄する。新enumを旧consumerへ送る場合は互換negotiationが必要になるため、v1.0では固定集合を使う。missing capabilityをvalidと推定しない。
- malformedな外部入力は例外をworker外へ投げず、error counterに分離する。datagramの全検証後にcache更新する。
- Protocol libraryはdata model/schema/codec/validationだけ。socket、HID、PICO runtime、OpenVR、GUI、solverへ依存しない。
- PC既定port：Observation ingress=`127.0.0.1:29810`（Bridgeだけがbind）、MTP consumer=`127.0.0.1:29811`（MonakaVR）、SteamVR driver内部feed=`127.0.0.1:29812`（同じMTP codec）、Utility用Observation mirror=`127.0.0.1:29813`（任意consumer）。全て設定可。LAN bindを自動で行わない。
- Backendは29810へ送る。Bridgeは出力policy `steamvr/monaka/both/disabled`を持つ。29813の観測monitoringはpolicyに依存せず、1つの遅いconsumerが他の出力を止めない。per-device latest-only cacheと有界queueを使用する。
- 旧29765はPICO Backend内のHMD ingressとして維持。29766/29767/29768は旧compatibility用だけに残し、新protocolを同じportへ混在させない。C1移行後は旧game fan-outを終了する。

### C1.6 Task 1が提供するAPIとhandoff

正規artifact名：`monaka-protocol-kit-v1.0.zip`、同梱`protocol.lock.json`。中に`schema/tracker-observation.schema.json`、`schema/monaka-tracking.schema.json`、`docs/C1.md`、`cpp/`、`jvm/`、`fixtures/`、`SHA256SUMS`、依存license/noticeを含める。実際のschema commit、C1本文hash、依存revisionは同梱lockに記録し、zip自身のSHA256は外部handoff manifestに記録する。現時点で未来のcommit値を捏造しない。

- C++17 namespace `monaka::protocol::v1`、型`TrackerObservation` / `ObservationDeviceState` / `MtpPose` / `MtpTrackerState` / `Envelope` / `Error`。
- API：`bool DecodeEnvelope(const uint8_t* data, size_t size, Envelope& out, Error& error)`、`bool EncodeEnvelope(const Envelope& value, std::string& utf8, Error& error)`。失敗時outは不変、error.codeを返す。public headerは`monaka/protocol/v1/codec.hpp`、CMake targetは`MonakaProtocol::Codec`。
- JVM 17 package `dev.monaka.protocol.v1`、同じ型名。`MonakaCodec.decodeEnvelope(ByteArray): DecodeResult`、`MonakaCodec.encodeEnvelope(Envelope): EncodeResult`。resultはSuccess(value)/Failure(code,message)のsealed型。配布JARは`monaka-protocol-jvm-0.1.0.jar`、runtime dependenciesとexact versionを同梱manifestに列記する。
- error.codeは`MalformedJson/InvalidUtf8/DuplicateKey/TooLarge/UnsupportedVersion/UnsupportedMessage/UnsupportedValue/MissingField/InvalidType/OutOfRange/InvalidQuaternion/InconsistentValidity`。
- 後続Taskはkitを自repoの`third_party/monaka-protocol/`へ固定取り込みし、`dependencies/monaka-protocol.lock.json`に元commit/hashを保存する。独自codec再実装禁止。kit更新は同じschema/versionを全repoへ適用する。別repoのlive checkoutは不要。
- Task 1のfixture（通常pose、orientation-only、unknown battery、session reset、large timestamp、malformed）を全consumerで共有する。自己round-tripだけでなくC++ encode→JVM decode、JVM encode→C++ decodeのsemantic一致をテストする。

一次仕様：[RFC 8259](https://www.rfc-editor.org/rfc/rfc8259)、[JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core)。field名、version、port、quality値、時刻運用は本案独自の設計である。
<!-- END COMMON C1 -->

## 最終報告形式（必須）

以下を省略せず、実際に実行した内容で埋める。実行していないbuild/test/hardwareをPASSにしない。

```text
repository:
audited_base_branch:
audited_base_sha:
actual_base_branch:
actual_base_sha:
base_change_reason:
work_branch: refactor/monaka-layer-separation
HEAD_SHA:
protocol_version:
schema_commit:
protocol_kit_sha256:
contract_c1_sha256:

changed_files: 各ファイルの変更理由を併記
build_result: コマンド / 環境 / PASS・FAIL・NOT RUN
unit_test_result:
mock_or_cross_language_result:
hardware_validation: 実施した項目 / 未確認項目
compatibility_status:
phase_or_DoD_status:
unresolved_issues:
followup_required_in_other_repos:
handoff_artifacts: ファイル名 / SHA256 / 元commit
```

変更ファイル一覧だけでなく、「どの責務を残し、どれを移したか」「旧consumerを壊さず切り替えられるか」を短く説明する。未来のschema commit/HEADは仮のSHAで埋めない。
