# セットアップガイド（電子マネー / Vault Provider 連携）

Man10Bank を **Vault(Economy) の Provider** として動かし、電子マネー残高を
`Man10BankService`（C# / MySQL）で一元管理するための導入手順をまとめる。
BungeeCord / Velocity 構成でサーバーを跨いでも電子マネーが同期される。

- 設計の詳細・整合性モデル: [`VaultProvider.md`](./VaultProvider.md)
- 銀行残高 API（既存）: [`BankAPI.md`](./BankAPI.md)
- サービス側の詳細設定: [`man10bankservice/README.md`](../../man10bankservice/README.md)

> 用語: プレイヤーが直接使う残高＝「電子マネー」（Vault の `getBalance` が返す値、DB は `user_vault`）。
> 銀行残高（`user_bank`）とは別管理で、ATM や `/deposit` `/withdraw` で相互に移動する。

---

## 目次

- [1. 全体構成](#1-全体構成)
- [2. 必要環境](#2-必要環境)
- [3. サービス（man10bankservice）の起動](#3-サービスman10bankserviceの起動)
- [4. プラグイン（man10bank）の導入](#4-プラグインman10bankの導入)
- [5. Vault Provider の有効化（最重要）](#5-vault-provider-の有効化最重要)
- [6. 動作確認](#6-動作確認)
- [7. config.yml リファレンス](#7-configyml-リファレンス)
- [8. 段階導入・ロールバック](#8-段階導入ロールバック)
- [9. トラブルシューティング](#9-トラブルシューティング)

---

## 1. 全体構成

```
   ┌──── Paper #1 (lobby) ────┐      ┌──── Paper #2 (survival) ──┐
   │ Man10Bank                │      │ Man10Bank                 │
   │  = Vault(Economy)Provider │      │  = Vault(Economy)Provider │
   │  └ VaultCache(UUID→残高)  │      │  └ VaultCache             │
   └───┬──────────────┬───────┘      └───┬───────────┬──────────┘
       │ REST(Bearer) │ WebSocket        │ REST       │ WebSocket
       ▼              ▲ push/presence     ▼            ▲
   ┌─────────────────── Man10BankService (C#) ───────────────────┐
   │  /api/Vault/* (REST)  +  /api/Vault/ws (push & presence)     │
   │  user_vault(電子マネーの真実) / user_bank(銀行残高)  ← MySQL  │
   └─────────────────────────────────────────────────────────────┘
```

- **読み（残高表示）はローカルキャッシュから即返す**（HTTP をメインスレッドで待たない）。
- **書き（入出金）はキャッシュを楽観更新し、REST へ非同期 write-through**。確定はサービス側で原子的に行う。
- サービスは確定後に対象プレイヤーの在席サーバー 1 台へ **WebSocket で残高を push** し、各サーバーのキャッシュを真実へ収束させる。

---

## 2. 必要環境

| 対象 | 要件 |
|---|---|
| サービス | Docker（`docker compose`）。または .NET 9 SDK で直接実行も可。MySQL 8.x。 |
| プラグイン | Paper 1.21 系、JDK 21（ビルド時）、**Vault プラグイン（必須）** |
| 経済プラグイン | EssentialsX 等の**他の Economy Provider は無効化**しておく（[5](#5-vault-provider-の有効化最重要)） |

---

## 3. サービス（man10bankservice）の起動

### 3.1 イメージのビルド

`docker-compose.yml` の `app` は `man10-bank-service:local` を使う。

```bash
# man10bankservice ディレクトリで
docker compose build app
# もしくは
docker build -t man10-bank-service:local .
```

### 3.2 API キーの用意

本番（`ASPNETCORE_ENVIRONMENT=Production`、compose の既定）では **API キー必須**。
未設定だと起動時に失敗する（fail-closed）。長いランダム文字列を 1 つ生成しておく。

```bash
openssl rand -hex 32   # 例: 生成したキーを控える
```

書き込み（POST）には `admin` スコープが必要なので、**admin スコープのキー**を用意する。
WebSocket（`/api/Vault/ws`）は認証必須（read/admin いずれでも可）だが、プラグインは
同じキーを REST と WebSocket の両方に使うため、**admin スコープのキーを 1 つ**設定すればよい。

### 3.3 起動

`docker-compose.yml` の `app` に環境変数でキーを注入する（または `appsettings.json` の `Auth` セクション）。

```yaml
# docker-compose.yml の app.environment へ追記
Auth__ApiKeys__0__Key: "<3.2で生成したキー>"
Auth__ApiKeys__0__Name: "man10bank-plugin"
Auth__ApiKeys__0__Scopes__0: "admin"
```

```bash
docker compose up -d        # DB(MySQL) + app が起動する
```

- DB スキーマは初回起動時に `sql/db.sql` が自動適用される（**電子マネー用の `user_vault` /
  `vault_log` テーブルを含む**）。
- 既存 DB へ後から追加する場合は `sql/008_add_user_vault.sql` を流す。

### 3.4 疎通確認

```bash
curl -s http://localhost:8080/api/Health
# {"service":"Man10BankService", ... ,"database":true} なら OK
```

`"database":true` にならない場合は [man10bankservice/README.md](../../man10bankservice/README.md) の
「MySQLに繋がらない場合」を参照。

---

## 4. プラグイン（man10bank）の導入

### 4.1 ビルドと配置

```bash
# man10bank ディレクトリで
gradle clean build      # build/libs/Man10Bank-<version>.jar を生成
```

生成された JAR を各 Paper サーバーの `plugins/` へ配置し、`Vault` プラグインも導入する。
（開発時は `.env` に `DEPLOY_DIR` を設定して `sh ./local-deploy.sh` でも可。）

### 4.2 config.yml の基本設定

初回起動で `plugins/Man10Bank/config.yml` が生成される。サービスへの接続情報を設定する。

```yaml
api:
  baseUrl: "http://localhost:8080"   # サービスの疎通確認に使った URL。
                                       # 同一 docker compose 内からは http://bank:8080 等
  apiKey: "<3.2で設定した admin キー>"  # サービスの Auth:ApiKeys と同じ値
  timeout:
    requestMs: 10000
    connectMs: 3000
    socketMs: 10000
  retries: 2

serverName: "lobby"   # （任意）presence 識別に使うこのサーバーの名前。
                        # 未設定なら Bukkit のサーバー名を使用。BungeeCord 配下では
                        # サーバーごとに一意な名前を推奨（lobby / survival など）。
```

> **WebSocket の URL はプラグインが `baseUrl` から自動生成する**（`http://`→`ws://`、`https://`→`wss://`、
> パス `/api/Vault/ws`）。本番では残高を扱うため **`baseUrl` を `https://` にして wss を使う**ことを推奨（設計 §12）。

---

## 5. Vault Provider の有効化（最重要）

### 5.1 config.yml の vault セクション

```yaml
# 電子マネー(Vault Provider)設定
vault:
  providerEnabled: true        # Man10Bank を Vault(Economy) Provider として登録する
  currencyNameSingular: "円"
  currencyNamePlural: "円"
```

### 5.2 競合する Economy Provider を必ず無効化する

同時に複数の Economy Provider が登録されていると `ServicePriority` 勝負になり、
**意図しない Provider が実効になって電子マネーが別経済へ流れ、整合性が崩れる**。
EssentialsX を使っている場合は経済機能を無効化する。

```yaml
# Essentials の config.yml
economy: false
```

### 5.3 登録の検証とフェイルセーフ（自動）

プラグインは起動時に以下を自動で行う（設計 §8.3）。運用者の操作は不要。

1. `Economy` Provider を `ServicePriority.High` で登録する。
2. 登録後に**実効 Provider が Man10Bank 自身であることを検証**する。
3. **検証に失敗した場合（競合・登録例外・Vault 不在）**は `severe` ログを出し、
   **サーバーをホワイトリスト化（`/whitelist on`）して新規参加を遮断**する安全弁が作動する。
   → この状態になったら原因（競合 Economy の無効化漏れ・Vault 不在など）を解消し、
   　 サーバーを再起動 → 正常登録を確認してから `/whitelist off` する。

成功時はサービスへ WebSocket 同期接続を開始し、内部利用者（残高表示・ATM 等）も
電子マネー＝`user_vault` を参照するようになる。

---

## 6. 動作確認

起動ログに次の 2 行が出れば登録・同期は成功:

```
[Man10Bank] Man10Bank を Vault(Economy) Provider として登録しました。
[Man10Bank] Vault同期WebSocketに接続しました
```

| 操作 | 期待 |
|---|---|
| `/bankop health` | サービスへの疎通 OK |
| プレイヤーが join → `/bal` | 電子マネー残高が表示される（join 時にプリロード） |
| `/pay <相手> <金額>` | **同一サーバーに在席する相手**へ電子マネー送金（オフライン相手は不可） |
| `/deposit` `/withdraw` `/atm` | 電子マネー ⇄ 銀行の入出金 |
| `/bankop editvault <player> <金額> <理由>` | 管理者が電子マネー残高を設定（**オフライン対象も可**、`POST /api/Vault/set` 経由） |
| 他サーバーでの set 等 | 対象が在席するサーバーのキャッシュへ push で即時反映 |

---

## 7. config.yml リファレンス

| キー | 既定 | 説明 |
|---|---|---|
| `api.baseUrl` | `http://localhost:8080` | サービスのベース URL（REST と WebSocket の両方に使う） |
| `api.apiKey` | `""` | サービスの `Auth:ApiKeys` と同じ admin スコープのキー |
| `api.timeout.*` / `api.retries` | 各既定値 | HTTP タイムアウト / リトライ（POST はリトライしない） |
| `serverName` | Bukkit のサーバー名 | presence 識別子。BungeeCord 配下では一意名を推奨 |
| `vault.providerEnabled` | `true` | Provider 登録の ON/OFF（段階導入・ロールバック用） |
| `vault.currencyNameSingular` / `Plural` | `"円"` | `Economy.currencyName*` が返す通貨名 |

> 電子マネーは **単一通貨・整数（円）**。VaultUnlocked / 多通貨・小数は非対応。

---

## 8. 段階導入・ロールバック

- 切替前の検証中や、問題発生時のロールバックは `vault.providerEnabled: false` にして再起動する。
  - `false` の間は **Provider 登録自体を行わない**ため、フェイルセーフ（ホワイトリスト化）も作動しない。
  - 既存の経済プラグイン（EssentialsX 等）を Provider に戻せる。
- 既存電子マネーの `user_vault` への取り込み（移行）は別タスク。`user_vault` は当面ゼロ初期化を許容する。

---

## 9. トラブルシューティング

| 症状 | 原因 / 対処 |
|---|---|
| 起動直後にサーバーがホワイトリスト化された | Provider 登録の検証失敗。`severe` ログの競合相手名/例外を確認し、競合 Economy（EssentialsX の `economy: true` 等）を無効化、または Vault 導入を確認 → 再起動 → `/whitelist off`。 |
| `Vault(Economy) が見つかりません` / Vault 不在 | `Vault` プラグインを導入する（`softdepend` のため Vault より後にロードされる）。 |
| `Vault同期WebSocketが切断されました` が繰り返す | `baseUrl` の到達性、`apiKey` の一致、サービス稼働を確認。切断中も自動で指数バックオフ再接続し、再接続時に在席者を再同期する。 |
| `/bal` が常に 0 円 | サービス未接続 / `apiKey` 不一致でプリロードに失敗している可能性。`/bankop health` とサービスログ、`api.apiKey` を確認。 |
| `/pay` が「相手がこのサーバーにいない」 | 仕様。電子マネー送金は **送受信者がともに同一サーバー在席時のみ**（オフライン相手は別機能）。 |
| 出金が時々 409 で巻き戻る | 楽観出金が権威と競合した稀ケース。サービスが権威残高を再取得してキャッシュを補正する（設計 §4.4）。 |

---

詳細な整合性モデル・障害時の挙動・API 仕様は [`VaultProvider.md`](./VaultProvider.md) を参照。
