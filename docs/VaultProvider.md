# VaultProvider 設計書

Man10Bank を **Vault(Economy) の Provider** 化し、プレイヤーの電子マネーを
`Man10BankService`（C# / MySQL）で一元管理する。BungeeCord / Velocity でのサーバー移動を跨いでも
電子マネーが同期され、**`man10bankservice` が参照する DB 値を唯一の真実（source of truth）** とする。

- 対象プラグイン: [`src/main/java/red/man10/man10bank`](../src/main/java/red/man10/man10bank)
- 対象サービス: [`man10bankservice/Man10BankService`](../../man10bankservice/Man10BankService)
- 関連: [`BankAPI.md`](./BankAPI.md)（既存の Bank=銀行残高 API 仕様）

> 用語注: 本書では Bukkit プラグインの基盤名のみ「Vault(Economy) / VaultAPI」と表記する。
> プレイヤーが直接使える残高の概念は「電子マネー」、その DB/コード上の識別子は `vault`（例 `user_vault`）で統一する。

> 本書は設計の合意形成用ドラフトです。検討事項の決定状況は [13](#13-検討事項の決定状況) に集約（未決は移行のみ）。実装はまだ行いません。

---

## 目次

- [1. 背景と方針](#1-背景と方針)
- [2. 用語](#2-用語)
- [3. 全体アーキテクチャ](#3-全体アーキテクチャ)
- [4. 整合性モデル（最重要）](#4-整合性モデル最重要)
- [5. サーバー間同期（プッシュ）](#5-サーバー間同期プッシュ)
- [6. データモデル（C#）](#6-データモデルc)
- [7. サービス API 追加（C#）](#7-サービス-api-追加c)
- [8. プラグイン側設計（Kotlin）](#8-プラグイン側設計kotlin)
- [9. 既存処理への影響](#9-既存処理への影響)
- [10. 金額・型の扱い](#10-金額型の扱い)
- [11. 障害・エッジケース](#11-障害エッジケース)
- [12. セキュリティ](#12-セキュリティ)
- [13. 検討事項の決定状況](#13-検討事項の決定状況)
- [14. 実装タスク（チェックリスト）](#14-実装タスクチェックリスト)

---

## 1. 背景と方針

現状の Man10Bank は **Vault の利用者（Consumer）** で、電子マネーは EssentialsX 等の
外部経済プラグインが保持している。[`VaultManager`](../src/main/java/red/man10/man10bank/service/VaultManager.kt)
が `ServicesManager.getRegistration(Economy)` で外部 Provider を取得して読み書きしている。

本設計では向きを反転し、**Man10Bank 自身が Vault(Economy) の Provider** になる。電子マネー残高は
`man10bankservice` の新テーブルで保持し、各 Paper サーバーはそのキャッシュを持つ。

### 確定した方針（事前合意）

| # | 論点 | 決定 |
|---|------|------|
| 1 | 電子マネー残高の実体 | **新規テーブル `user_vault` を追加**。`user_bank`（銀行残高）とは別管理。電子マネー ⇄ 銀行の2層と ATM は維持。 |
| 2 | サーバー間同期 | **C# サービスからプッシュ**（残高変更イベントを各 Paper へ配信） |
| 3 | 対応 API 範囲 | **旧 Vault（単一通貨・`double`）のみ**。VaultUnlocked / 多通貨は**非対応**（単一通貨固定） |
| 4 | 既存電子マネーの移行 | **一旦保留**。切替時の取り込みは別タスク。`user_vault` は当面ゼロ初期化を許容 |

---

## 2. 用語

| 用語 | 意味 |
|------|------|
| 電子マネー | プレイヤーが直接使える残高。**Vault(Economy) の `getBalance` が返す値**。本設計で `user_vault` に格納する。識別子は `vault`。 |
| 銀行 / 銀行残高 | `user_bank.Balance`。ATM や `/deposit` `/withdraw` で電子マネーと相互移動する。今回の対象外（既存維持）。 |
| Provider | `net.milkbowl.vault.economy.Economy` を実装し `ServicesManager` に登録する側。**本設計で Man10Bank が担う。** |
| Consumer | 登録済み `Economy` を取得して使う側（ショップ等）。Man10Bank も内部利用者として残る。 |
| 真実（source of truth） | `man10bankservice` が参照する DB（`user_vault`）の値。各サーバーのキャッシュは従属。 |
| 単一書き込み者 | ある電子マネーを能動的に減らせるのは「そのプレイヤーが今いる 1 サーバー」だけ、という不変条件。[4.1](#41-単一書き込み者の不変条件) 参照。 |

---

## 3. 全体アーキテクチャ

```
   ┌────────────── Paper #1 (lobby) ─────────────┐   ┌──── Paper #2 (survival) ────┐
   │  Man10Economy : Economy  ← 他PL(ショップ等)   │   │  Man10Economy : Economy      │
   │      │  getBalance/has/withdraw/deposit       │   │      │                       │
   │      ▼ (同期・メインスレッド)                  │   │      ▼                       │
   │  VaultService ── VaultCache(UUID→{bal,ver})   │   │  VaultService ── VaultCache  │
   │      │ ① 読み: キャッシュ即返し               │   │      │                       │
   │      │ ② 書き: REST write-through(非同期)      │   │      │                       │
   │      ▼                            ▲ ④ push    │   │      ▼          ▲ push        │
   └──────┼────────────────────────────┼──────────┘   └──────┼──────────┼─────────────┘
          │ REST(コマンド/Bearer)       │ WebSocket           │ REST      │ WebSocket
          ▼                            │                     ▼           │
   ┌────────────────────── Man10BankService (C#) ───────────────────────┐
   │  VaultController(REST) ──► BankService.RunExclusiveAsync            │
   │      原子的に user_vault を更新 + vault_log + version++             │
   │      └─► ③ VaultWsHub が在席サーバー(presence)へ targeting push     │
   │  user_vault(真実) / user_bank(銀行残高) は同一 DB → 相互移動は1Txで原子的 │
   └─────────────────────────── MySQL ──────────────────────────────────┘
```

要点:
- **読み（getBalance/has）はローカルキャッシュから同期で即返す**。HTTP をメインスレッドで待たない。
- **書き（deposit/withdraw）はキャッシュを楽観更新し、REST へ非同期 write-through**。真実は常にサービス側で原子的に確定。
- **トランスポートは 2 系統**: コマンド（deposit/withdraw/transfer/balance/move）は **REST**（要求→確定応答）、サーバー起点の push と presence は **WebSocket**。[§5](#5-サーバー間同期プッシュ)。
- **サービスは変更のたびに「確定残高＋version」を、対象 UUID が在席する 1 サーバーへ WebSocket で targeting push**（presence ベース）。受けたサーバーだけがキャッシュを真実へ収束させる（オンラインは常に最大1サーバー、オフラインなら誰にも送らない）。
- 電子マネーも銀行残高も同一 DB に入るため、**ATM / `/deposit` / `/withdraw`（電子マネー⇄銀行）はサービス内の単一トランザクション**にでき、現状のクライアント側補償（Saga）が不要になる（[9](#9-既存処理への影響)）。

---

## 4. 整合性モデル（最重要）

Vault(Economy) の `getBalance` / `has` / `withdrawPlayer` / `depositPlayer` は **同期メソッドでメインスレッドから呼ばれる**。
一方、真実は HTTP の先にある。メインスレッドで HTTP ブロッキングは厳禁（TPS 崩壊）。
よって **ローカルキャッシュ＋write-through＋サーバー権威** で解く。RedisEconomy と同型の構造。

### 4.1 単一書き込み者の不変条件

Bungee/Velocity では **1 人のプレイヤーは常に 1 つのバックエンドサーバーにのみオンライン**。
これを設計の前提に置く:

- プレイヤー本人の電子マネーを **能動的に減らす（withdraw/支払い）操作は、本人が今いるサーバーでのみ発生**する。
- 他サーバー起因で電子マネーが変わるのは、**入金（他人からの送金）** か **管理操作（set/没収）** のみ。これらは必ずサービス経由（原子的な差分適用）で行い、push で対象サーバーのキャッシュを補正する。

→ 自サーバーのキャッシュは「自分の支払い」については唯一の書き手なので、**通常プレイ中のキャッシュは権威に一致**する。

### 4.2 読み・書きの挙動

| 操作 | 挙動 |
|------|------|
| `getBalance(p)` | キャッシュ値を即返す（未ロードや口座無しは `0`）。 |
| `has(p, amt)` | キャッシュで `bal >= amt` を判定（楽観）。 |
| `depositPlayer(p, amt)` | キャッシュを `+= amt` し、REST `vault/deposit`（原子的差分）を非同期投入。即 `SUCCESS` を返す。 |
| `withdrawPlayer(p, amt)` | キャッシュ `bal < amt` なら即 `FAILURE`。足りれば `-= amt` し REST `vault/withdraw` を非同期投入、即 `SUCCESS`。 |

サービス側は **行ロック（`SELECT … FOR UPDATE`）下で再チェックしてから差分適用** するため、最終的な正しさは常にサービスが担保する（既存 [`BankService`](../../man10bankservice/Man10BankService/Services/BankService.cs) と同じ機構を流用）。

### 4.3 楽観適用の収束（version 方式）

push イベントは **「確定後の残高」と「単調増加 version」** を運ぶ。各サーバーは

```
if (event.version > cache.version) { cache.balance = event.balance; cache.version = event.version }
```

で適用する。これにより:
- **冪等**（同じイベントを二重受信しても安全）かつ **順序安全**（古いイベントで新しい値を壊さない）。
- 楽観更新がズレても、直後の確定 push が **キャッシュを真実へ強制収束** させる（自己修復）。

### 4.4 withdraw の既知リスクと緩和

楽観 withdraw は「キャッシュが真実より多い（stale-high）」瞬間に二重支払いの余地がある。
ただし [4.1](#41-単一書き込み者の不変条件) より stale-high は **管理操作 / 他サーバー没収が支払いと同時刻に競合した場合のみ**で、極めて稀。

- 緩和1: 外部変更は必ず push で即時補正されるため窓が小さい。
- 緩和2: write-through が `409 InsufficientFunds` で失格した場合は **権威残高を再取得してキャッシュを上書き** し、構造化ログを残す（既存 DESIGN 3.6 の補償ログ方針に準拠）。リトライで差分を二重適用しない。
- 任意強化: 高額 withdraw のみ同期事前承認（サービスに予約→確定）も理論上は可能だが、**採用しない**（[§13.1](#131-決定事項確定)-(4)）。

> Minecraft 経済での楽観モデルの定番トレードオフ。RedisEconomy 等も同等の割り切りで運用している。

### 4.5 オフライン時の扱い

キャッシュは **オンラインのみ常駐**（join ロード / quit 退避）。オフラインは常駐させず、真実は `user_vault`（DB）に置く。
Vault(Economy) は `OfflinePlayer` を取るため、オフライン相手の操作はキャッシュに頼らず次のとおり捌く:

| 操作 | 扱い |
|------|------|
| 入金 `depositPlayer(offline)` | **write-through のみで完結**（サービスが原子的に `+= amount`）。キャッシュ不要・真実は常に正しい。送金/報酬/売上など大半のユースケースはここで成立。 |
| 読み `getBalance`/`has`（未キャッシュ） | 同期で HTTP を待てないため **ベストエフォート**。`0`/`false` を返しつつ非同期ロードし、次回呼び出しから正しい値を返す。既知の制約。 |
| 出金 `withdrawPlayer`（未キャッシュ） | **(B) 楽観 write-through**。サーバーが行ロック下で `409 InsufficientFunds` を判定し、失格なら権威残高を再取得してキャッシュ補正＋構造化ログ（[4.4](#44-withdraw-の既知リスクと緩和)）。 |

push されたオフライン対象の変更は全サーバーで無視され、次回 join 時のプリロードで反映される（[11](#11-障害エッジケース)）。

### 4.6 2つの入口（同期と非同期）

正しさが要る経路は同期 Vault に縛られない。Man10Bank は電子マネーへの入口を **2 つ** 持つ:

| 入口 | 性質 | 主な利用者 | 整合性 |
|------|------|-----------|--------|
| `Man10Economy : Economy`（Vault） | **同期**（契約上 `EconomyResponse` を即返す） | 外部プラグイン（ショップ等） | **楽観**：キャッシュ＋fire-and-forget write-through。オフライン出金は [4.5](#45-オフライン時の扱い) の (B)。 |
| `VaultService` のネイティブ API | **`suspend`・結果付き**（サーバーの確定応答を `await`） | Man10Bank 自身（`/pay`・ATM・`/deposit`）／Man10 対応プラグイン | **権威的**：`409` まで待って成否を返す＝楽観リスクなし。**オフライン相手でも厳密**。 |

→「非同期で操作できる関数を用意するのが正しいか？」は **Yes**。ただし Vault の同期契約は変えられないため、
**ネイティブ非同期 API は“権威的な正道”を追加するものであり、Vault の楽観経路を置換するものではない**
（外部の同期 Vault 呼び出しは楽観のまま）。`/pay` でオフライン相手へ送る等、Man10 制御下のフローは
非同期 API を使えば **オフラインでも `409` を待って厳密化** できる。これは既存 [`BankAPI.md`](./BankAPI.md) が
同期/非同期の結果付き API を併設しているのと同じ思想。

---

## 5. サーバー間同期（プッシュ）

**確定（[§13.1](#131-決定事項確定)-1/3）**: コマンドは REST、**サーバー起点の push と presence は WebSocket**（双方向）。
push は対象 UUID が在席する **1 サーバーのみへ targeting**（presence ベース）。

### 5.1 トランスポートの確定理由

| 種別 | 方式 | 理由 |
|------|------|------|
| コマンド（deposit/withdraw/transfer/balance/move） | **REST** | 要求→確定応答。既存 [`HttpClientFactory`](../src/main/java/red/man10/man10bank/net/HttpClientFactory.kt) / `ProblemDetails` / `RequireWriteScope` / 「POST はリトライしない」を流用でき、`409` 等の HTTP ステータスがそのまま結果になる。 |
| push（残高変更通知）＋ presence | **WebSocket** | サーバー起点通知に最適で、**presence を同一ソケットで運べる**ため targeting が自然。接続＝生存で stale 検知が容易。SSE（下り一方向）は presence を別経路にする必要があり不採用。 |

SignalR も候補だが Kotlin 側クライアントが重く、生 WebSocket（Ktor ⇄ ASP.NET Core）で十分なため不採用。

### 5.2 接続とライフサイクル

- 各 Paper サーバーは起動時にサービスへ **WebSocket を 1 本**張る（[`HttpClientFactory`](../src/main/java/red/man10/man10bank/net/HttpClientFactory.kt) と同じ Bearer を upgrade リクエストに付与）。
- 接続確立後、自サーバーのオンライン全員を **presence 登録**（[5.4](#54-presence-プロトコル)）し、残高を full resync。
- `ping`/`pong` ハートビートで生存監視。切断時は指数バックオフで再接続し、再接続後に presence 再登録＋full resync。

### 5.3 残高変更イベント

書き込みトランザクションの **コミット後** に、サービスが対象 UUID の在席サーバーへ送る（在席不明なら送らない）:

```jsonc
{
  "type": "balance",
  "uuid": "0a1b...-....",
  "balance": 123450,          // 確定後の電子マネー残高（整数・円）
  "version": 42,              // user_vault.Version（単調増加）
  "cause": "DEPOSIT|WITHDRAW|TRANSFER|SET|BANK_MOVE",
  "originServer": "lobby",    // 変更を起こしたサーバー名（診断用）
  "ts": "2026-06-16T10:00:00Z"
}
```

適用は version 方式（[4.3](#43-楽観適用の収束version-方式)）。`event.version > cache.version` のときだけ反映するため、冪等かつ順序安全。

### 5.4 presence プロトコル

同じ WebSocket 上で在席を通知し、サービスは `UUID→接続（サーバー）` を保持する。

```jsonc
{ "type": "presence", "action": "join|quit", "uuid": "....", "server": "survival" }
```

- `join`: サービスが `UUID→この接続` を登録。以後その UUID の push はこの接続へ targeting。
- `quit`: 登録解除。以後 push しない（次回 join で再登録）。
- **接続断**: その接続の presence をサーバー単位で一括失効。Paper は再接続時に在席者を再登録。
- サーバー移動で同一 UUID が別サーバーから `join` した場合は **後勝ち**で上書き（旧サーバーは quit 済み or 接続断で失効）。

### 5.5 ギャップ回復

- WebSocket 切断中の変更は取りこぼし得る。**再接続時に在席者全員の残高を full resync**（REST で一括取得）し、キャッシュを権威で上書きする。
- 将来: サービス側にイベントのリングバッファ＋シーケンス番号を持ち、再接続時に差分リプレイ（full resync の負荷削減）。

---

## 6. データモデル（C#）

### 6.1 `user_vault`（新規・真実）

[`UserBank`](../../man10bankservice/Man10BankService/Models/Database/UserBank.cs) を踏襲し `Version` を追加する。

```csharp
public class UserVault
{
    public int Id { get; set; }
    [StringLength(16)] public required string Player { get; set; }
    [StringLength(36)] public required string Uuid { get; set; }   // UNIQUE
    public decimal Balance { get; set; }                          // 残高（負値はサービス層で防止）
    public long Version { get; set; }                             // 変更毎に ++。push 順序の基準
}
```

```sql
-- sql/008_add_user_vault.sql（番号は採番に従う）
create table user_vault (
    id      int auto_increment primary key,
    player  varchar(16)   not null,
    uuid    varchar(36)   not null,
    balance decimal(20)   not null default 0,
    version bigint        not null default 0,
    unique key uq_user_vault_uuid (uuid)
);
```

### 6.2 `vault_log`（取引ログ）

[`MoneyLog`](../../man10bankservice/Man10BankService/Models/Database/MoneyLog.cs) と同形の電子マネー用ログを新設する
（銀行の `money_log` と混ぜず専用テーブルに分離＝確定。[§13.1](#131-決定事項確定)-(2)）。

### 6.3 BankDbContext

[`BankDbContext`](../../man10bankservice/Man10BankService/Data/BankDbContext.cs) に
`DbSet<UserVault>` / `DbSet<VaultLog>` を追加し、テーブル名・UNIQUE・decimal 精度をマッピングする。

---

## 7. サービス API 追加（C#）

### 7.1 単一書き込みキューの共用

電子マネーも銀行残高も **同じ直列化キュー（[`BankService.RunExclusiveAsync`](../../man10bankservice/Man10BankService/Services/BankService.cs)）** を通す。
これにより **電子マネー⇄銀行を跨ぐ操作を 1 トランザクションで原子化** でき、行ロックの取得順も一元管理できる。
（実装は `BankService` 拡張 or 共通の Ledger サービスへ集約。デッドロック回避のためロック取得は UUID/テーブル順を固定。）

### 7.2 `VaultController`（`/api/Vault`）

[`BankController`](../../man10bankservice/Man10BankService/Controllers/BankController.cs) に倣う。書き込みは `RequireWriteScope`。

| メソッド | 概要 | 返却 |
|---|---|---|
| `GET  {uuid}/balance` | 電子マネー残高取得 | `{ balance, version }` |
| `GET  {uuid}/logs` | 取引ログ | `VaultLog[]` |
| `POST deposit` | 原子的 `balance += amount`＋log＋`version++`＋push | `{ balance, version }` |
| `POST withdraw` | 行ロック下で不足判定→`-= amount`。不足は `409` | `{ balance, version }` |
| `POST transfer` | 電子マネー→電子マネー（`/pay`）を単一Txで。両者 push | `{ balance, version }` |
| `POST set` | 管理用：絶対値設定（差分を log）＋push | `{ balance, version }` |
| `POST move`（電子マネー⇄銀行）| `user_vault` と `user_bank` を **1 Tx** で移動（ATM/`/deposit`/`/withdraw` 用） | 両残高 |
| `GET  ws`（WebSocket upgrade） | **push＋presence の双方向チャネル**（[5](#5-サーバー間同期プッシュ)）。Read スコープ | WebSocket |

各書き込みは確定後に [`VaultWsHub`](#73-vaultwshub) へ確定残高＋version を渡し、在席サーバーへ targeting push する。

### 7.3 VaultWsHub

- 各 Paper の **WebSocket 接続**と **presence（`UUID→接続`）** を管理するシングルトン。
- 残高変更は書き込みトランザクションの **コミット後** に、対象 UUID の在席接続へ **targeting push**（コミット前に流すと未確定値が漏れる）。在席不明なら送らない。
- `ping`/`pong` で生存監視。切断検知時はその接続の presence を一括失効（Paper 側は再接続→presence 再登録＋full resync）。
- バックプレッシャ対策を持ち、詰まった接続は切断する。

---

## 8. プラグイン側設計（Kotlin）

### 8.1 コンポーネント

| 新規 | 役割 |
|------|------|
| `economy/Man10Economy.kt` | `net.milkbowl.vault.economy.Economy` 実装。`VaultService` への薄いアダプタ。 |
| `service/vault/VaultService.kt` | 電子マネーの単一窓口。**同期**（キャッシュ参照、Vault 用）と **`suspend` 権威**（サーバー確定を `await`、ネイティブ用）の両 API を提供（[4.6](#46-2つの入口同期と非同期)）。write-through キュー＋再取得補正。 |
| `service/vault/VaultCache.kt` | `ConcurrentHashMap<UUID, Entry(balance, version)>`。読みは同期・スレッド安全。 |
| `service/vault/VaultSyncClient.kt` | サービスへの **WebSocket 接続**。presence(join/quit) 送信＋残高イベント受信、version でキャッシュ適用、切断時は再接続＋presence 再登録＋full resync。 |
| `api/VaultApiClient.kt` | `/api/Vault/*` の REST クライアント（既存 [`BankApiClient`](../src/main/java/red/man10/man10bank/api/BankApiClient.kt) と同形）。 |
| `listener/VaultLifecycleListener.kt` | join: 電子マネーをプリロード／quit: 保留書き込みを drain しキャッシュ退避。 |

### 8.2 `Economy` メソッド対応表

| Vault(Economy) メソッド | 実装 |
|---|---|
| `getName()` | `"Man10Bank"` |
| `isEnabled()` | プラグイン有効かつ同期接続済み |
| `fractionalDigits()` | `0`（円・整数。[10](#10-金額型の扱い)） |
| `format(amount)` | [`BalanceFormats`](../src/main/java/red/man10/man10bank/util/BalanceFormats.kt) 流用 |
| `currencyNameSingular/Plural()` | `"円"` 等（config 化） |
| `hasAccount` / `createPlayerAccount` | `VaultService.ensureAccount`（冪等。サービスに行が無ければ 0 で作成） |
| `getBalance` / `has` | `VaultCache` から同期取得 |
| `withdrawPlayer` / `depositPlayer` | [4.2](#42-読み書きの挙動) のとおり。`EconomyResponse(amount, newCacheBalance, SUCCESS/FAILURE, msg)` |
| `hasBankSupport()` | `false`（Vault の bank API は使わない。銀行残高は Man10Bank 独自コマンドで提供） |
| bank 系 | すべて `NOT_IMPLEMENTED` |

### 8.3 登録とロード順

```kotlin
// onEnable: Provider 登録（VaultManager.hook の Consumer 取得を置換）
server.servicesManager.register(
    Economy::class.java, man10Economy, this, ServicePriority.High
)
```

- `plugin.yml`: Vault より後にロードされるよう `softdepend: [Vault]`（既存）。`api-version 1.21`。
- **競合回避**: EssentialsX 等の Economy Provider を必ず無効化する（`economy: false` 等）。同時登録は `ServicePriority` 勝負になり事故る（[11](#11-障害エッジケース)）。
- 段階導入のため **config フラグ `vault.providerEnabled`** で登録の有無を切替可能にする（保留中の移行・ロールバック用）。

### 8.4 スレッドモデル

- `getBalance`/`has`/`withdrawPlayer`/`depositPlayer` は **メインスレッドで同期完結**（キャッシュ参照のみ）。
- write-through・WebSocket 送受信（残高イベント適用 / presence 送信）・resync は **`Dispatchers.IO`**（既存 `scope`）。`VaultCache` を介して受け渡す。
- 既存 [`VaultManager.onMainThread`](../src/main/java/red/man10/man10bank/service/VaultManager.kt) のような「外部 Economy をメインへディスパッチ」する必要は、自前実装になるため不要化できる。

---

## 9. 既存処理への影響

1. **`VaultManager`（Consumer）の置換**: 内部利用者（[`BankService`](../src/main/java/red/man10/man10bank/service/BankService.kt)・`AtmService`・`EstateService`・`BalanceCommand`・`AtmCommand`・`BankOpCommand`）は `VaultManager` を介して電子マネーへアクセスしている。`VaultService` へ重ねるか、`VaultManager` を `VaultService` のファサードに作り替えて呼び出し側の改修を最小化する。
2. **補償 Saga の撤廃**: [`BankService.deposit/withdraw`](../src/main/java/red/man10/man10bank/service/BankService.kt) は現在「電子マネー引落し＋HTTP 入金＋失敗時返金」を行う。電子マネーも銀行残高も同一 DB になるため、サービス側 `POST /api/Vault/move`（電子マネー⇄銀行 1 Tx）へ委譲し、**クライアント側補償ロジックを削除**できる（[`BankService.transfer`](../src/main/java/red/man10/man10bank/service/BankService.kt) が送金で補償を撤廃済みなのと同じ方向）。
3. **残高表示**: [`VaultManager.registerBalanceProvider`](../src/main/java/red/man10/man10bank/service/VaultManager.kt) の「電子マネー」表示を `VaultCache` 由来へ差し替え（[`BalanceRegistry`](../src/main/java/red/man10/man10bank/command/balance/BalanceRegistry.kt) の id=`vault` を維持）。
4. **ATM**: `AtmService` の電子マネー入出金を `VaultService` / `move` API に切替。
5. **送金コマンド**: 銀行送金 `/mpay`（既存 `PayCommand` ＝銀行残高送金）は変更なし。電子マネー送金は **新規 `/pay`** を追加し `POST /api/Vault/transfer`（電子マネー→電子マネー）へ委譲する。

---

## 10. 金額・型の扱い

- サーバー内部・DB は **整数（円）**。既存 [`BankService.normalizeAmount`](../src/main/java/red/man10/man10bank/service/BankService.kt)（小数切り捨て）に準拠し、電子マネー側も整数で統一。
- Vault(Economy) 契約は `double` だが、**値は常に整数**（`fractionalDigits()=0`）として扱い、境界でのみ変換。`double` 同士の累積演算で残高を保持しない。
- C# は `decimal(20)`。`user_bank` と同精度。
- 送金・支払いは **負数・ゼロを拒否**（既存方針）。

---

## 11. 障害・エッジケース

| ケース | 方針 |
|------|------|
| write-through 失敗（ネットワーク） | リトライで差分二重適用しない。権威残高を再取得しキャッシュ上書き＋構造化ログ。 |
| write-through が `409 InsufficientFunds` | 楽観 withdraw の取消。権威で上書き＋ログ（[4.4](#44-withdraw-の既知リスクと緩和)）。 |
| WebSocket 切断 | 指数バックオフで再接続→presence 再登録＋オンライン全員 full resync。切断中はサービスがその接続の presence を一括失効。 |
| サーバークラッシュ | 真実は DB。再起動後 join 時にプリロードで回復。書き戻し中の喪失は write-through 即時化で最小化。 |
| 二重 Provider 登録 | EssentialsX 等の economy を無効化。起動時に他 Provider 検出を警告ログ。 |
| 対象がオフライン（どこにも居ない） | サービスが `user_vault` を原子更新＋push。受信側が居なければ無視。次回 join 時にプリロードで反映。操作別の扱いは [4.5](#45-オフライン時の扱い)。 |
| 口座未作成 | `ensureAccount` を deposit/has の前段で冪等実行（行が無ければ 0 で作成）。 |
| POST のタイムアウト後に実は成功 | 再取得で権威を確認（既存方針：POST はリトライしない、[`HttpClientFactory`](../src/main/java/red/man10/man10bank/net/HttpClientFactory.kt)）。 |

---

## 12. セキュリティ

- REST 書き込みは既存 `RequireWriteScope`、WebSocket（`/api/Vault/ws`）は Read スコープを upgrade リクエストで要求。
- Paper→サービスは Bearer（[`HttpClientFactory`](../src/main/java/red/man10/man10bank/net/HttpClientFactory.kt) の `apiKey`）。鍵は `config.yml`（コミット禁止・.env 運用）。
- WebSocket は機微（残高）を含むため TLS（wss）前提。presence の `server` 名なりすまし防止に、接続トークンのスコープ/識別子で当該サーバーを束縛する。

---

## 13. 検討事項の決定状況

### 13.1 決定事項（確定）

| # | 論点 | 決定 |
|---|------|------|
| 1 | push トランスポート | **WebSocket**（双方向）。コマンドは REST 据え置き。SSE / SignalR は不採用（[§5.1](#51-トランスポートの確定理由)）。 |
| 2 | `vault_log` | **専用テーブルを新設**（[6.2](#62-vault_log取引ログ)）。`money_log` には相乗りしない。 |
| 3 | 配信ターゲティング | **presence ベースの targeting**。在席する 1 サーバーのみへ push（[§5.3](#53-残高変更イベント) / [§5.4](#54-presence-プロトコル)）。 |
| 4 | 高額 withdraw の同期事前承認 | **不要**。採用しない。楽観モデル＋[4.4](#44-withdraw-の既知リスクと緩和) の権威再取得補正で運用する。 |
| 5 | 通貨表示 | **小数桁なし**（`fractionalDigits()=0`）で確定。`currencyName`/`format` は config 化（円表示）。 |
| 7 | 送金コマンド | 銀行送金 `/mpay`（既存）は維持。電子マネー送金は **新規 `/pay` コマンド**を追加し `POST /api/Vault/transfer`（電子マネー→電子マネー）へ委譲する。 |
| 8 | VaultUnlocked / 多通貨 | **非対応**。単一通貨固定とし、`currency` 予約列・予約フィールドは持たない。 |
| 9 | オフライン時の扱い | [4.5](#45-オフライン時の扱い)/[4.6](#46-2つの入口同期と非同期) で確定。入金=write-through、読み=ベストエフォート、出金=**(B) 楽観**。厳密性が要る経路は **ネイティブ非同期 API** を使う。 |

### 13.2 継続検討（未決）

| # | 論点 | 状況 |
|---|------|------|
| 6 | 既存 EssentialsX 電子マネーの移行 | **保留**。最終的には DB テーブルの値コピー等で `user_vault` へ取り込む方向。凍結手順・ダウンタイム要否は別途設計。 |

---

## 14. 実装タスク（チェックリスト）

> 実装は本書の合意後に着手する。

### C# (`man10bankservice`)
- [ ] `Models/Database/UserVault.cs` / `VaultLog.cs`
- [ ] `sql/008_add_user_vault.sql`（`user_vault` / `vault_log`）
- [ ] `Data/BankDbContext.cs` に DbSet・マッピング追加
- [ ] `Data/DbLockHelper.cs` に `GetUserVaultForUpdateAsync`
- [ ] `Repositories/VaultRepository.cs`（`ChangeVaultBalanceCoreAsync`・version++）
- [ ] 電子マネー操作と電子マネー⇄銀行 `move` を `BankService` の直列キューへ載せる（単一書き込み者）
- [ ] `Services/VaultWsHub.cs`（WebSocket 接続管理・presence レジストリ `UUID→接続`・コミット後に在席接続へ targeting push）
- [ ] `Controllers/VaultController.cs`（balance/logs/deposit/withdraw/transfer/set/move）＋ WebSocket エンドポイント `/api/Vault/ws`（push＋presence、`app.UseWebSockets()`）
- [ ] 認証ポリシー適用（write=Write, ws=Read）
- [ ] テスト（`Test/`）：原子性・不足判定・version 単調増加・move の整合

### Kotlin (`man10bank`)
- [ ] `economy/Man10Economy.kt`（`Economy` 実装）
- [ ] `service/vault/VaultService.kt` / `VaultCache.kt` / `VaultSyncClient.kt`
- [ ] `api/VaultApiClient.kt` ＋ `api/model/request|response`（balance+version）
- [ ] `listener/VaultLifecycleListener.kt`（join: プリロード＋presence join 送信 / quit: drain＋presence quit 送信）
- [ ] `Man10Bank.onEnable`：Provider 登録（`ServicePriority.High`）・WebSocket 接続開始（`VaultSyncClient`）・`VaultManager` 置換
- [ ] 電子マネー送金コマンド `/pay`（新規。`VaultApiClient.transfer` 経由）＋ `plugin.yml` に `pay` 追加。銀行送金 `/mpay` は据え置き
- [ ] `config.yml`：`vault.providerEnabled`・sync エンドポイント・通貨表示設定
- [ ] `plugin.yml`：ロード順・他 economy 無効化の運用注記
- [ ] `BankService`：補償 Saga を `move` API へ置換し削除
- [ ] `BalanceRegistry` の「電子マネー」プロバイダを `VaultCache` 由来へ
- [ ] テスト（MockBukkit / Mock HTTP）：楽観 withdraw・version 収束・補正・登録

### 横断
- [ ] EssentialsX 等の旧 economy 無効化手順（運用ドキュメント）
- [ ] 段階ロールアウト：`providerEnabled=false` でデプロイ→単一サーバー検証→全面切替
- [ ] 既存電子マネー移行の設計（別タスク・保留）
