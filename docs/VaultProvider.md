# VaultProvider 設計書

Man10Bank を **Vault(Economy) の Provider** 化し、プレイヤーの電子マネーを
`Man10BankService`（C# / MySQL）の `user_vault` で一元管理する。

本設計では、外部ショップなどが使う既存 Vault API の **同期制約** と、
Man10BankService を唯一の真実（source of truth）にする **非同期・権威更新** を分離して扱う。

- 対象プラグイン: [`src/main/java/red/man10/man10bank`](../src/main/java/red/man10/man10bank)
- 対象サービス: [`man10bankservice/Man10BankService`](../../man10bankservice/Man10BankService)
- 関連: [`BankAPI.md`](./BankAPI.md)（既存の Bank=銀行残高 API 仕様）

> 用語注: Bukkit の経済連携基盤は「Vault(Economy) / Vault API」と表記する。
> プレイヤーが直接使える残高は「電子マネー」、DB/コード上の識別子は `vault`
> （例: `user_vault`）で統一する。

> 本書は設計の合意形成用ドラフト。データモデルと名称は維持し、アーキテクチャと整合性モデルを
> 本文の内容へ置き換える。

---

## 目次

- [1. 方針](#1-方針)
- [2. 用語](#2-用語)
- [3. 全体アーキテクチャ](#3-全体アーキテクチャ)
- [4. 取引経路](#4-取引経路)
- [5. 整合性モデル](#5-整合性モデル)
- [6. Provider キャッシュ](#6-provider-キャッシュ)
- [7. VaultService](#7-vaultservice)
- [8. Man10BankService API](#8-man10bankservice-api)
- [9. データモデル](#9-データモデル)
- [10. プラグイン側コンポーネント](#10-プラグイン側コンポーネント)
- [11. 既存処理への影響](#11-既存処理への影響)
- [12. 金額・型の扱い](#12-金額型の扱い)
- [13. 障害・エッジケース](#13-障害エッジケース)
- [14. セキュリティ](#14-セキュリティ)

---

## 1. 方針

現状の Man10Bank は Vault の Consumer として外部 Economy Provider を呼んでいる。
本設計では向きを反転し、Man10Bank が `net.milkbowl.vault.economy.Economy` を実装して
Vault Provider になる。

### 確定方針

| 論点 | 方針 |
|---|---|
| 電子マネーの実体 | `Man10BankService` の `user_vault` を唯一の真実（source of truth）にする。 |
| Vault Provider の制約 | Milkbowl/Vault 経由の Economy 操作は同期 API であり、Bukkit/Paper の前提上メインスレッドで扱う。Provider 内で HTTP を待たない。 |
| 外部ショップ経路 | 外部ショップなど既製 Vault API しか使えないプラグインだけが `Man10BankProvider` を通る。同期応答は Provider キャッシュで成立させる。 |
| 内製経路 | `/pay`、`/deposit`、`/withdraw`、ATM、Man10 系内製プラグインは Vault API を直接呼ばず、`Man10BankAPI -> VaultService -> Man10BankService` の非同期経路を使う。 |
| 収束責務 | `Man10BankProvider` は同期互換用のキャッシュを持つ。`VaultService` が Man10BankService との通信、確定応答、push、再同期を通じて Provider キャッシュを真実へ収束させる。 |
| 対応 API 範囲 | 旧 Vault Economy（単一通貨・`double`）のみ。VaultUnlocked / 多通貨は対象外。 |
| 既存電子マネー移行 | 別タスク。`user_vault` は初期値 0 を許容する。 |

---

## 2. 用語

| 用語 | 意味 |
|---|---|
| 電子マネー | プレイヤーが直接使える残高。Vault(Economy) の `getBalance` が返す値。DB 上は `user_vault`。 |
| 銀行残高 | `user_bank.Balance`。`/deposit` `/withdraw` で電子マネーと相互移動する既存の銀行残高。 |
| Man10BankProvider | `Economy` 実装。外部 Vault Consumer から同期で呼ばれる互換レイヤ。 |
| VaultService | プラグイン側の非同期サービス。Man10BankService への全 vault 書き込み、送信待ちキュー、再同期、Provider キャッシュ収束を担当する。 |
| Man10BankAPI | 内製プラグイン向けの公開 API。Vault API ではなく VaultService を呼ぶ。 |
| 在席サーバー | 対象プレイヤーが現在ログインしている Paper サーバー。Man10BankService の session claim で管理する。 |
| ローカル Vault 台帳 | VaultService が管理するオンラインプレイヤーのローカル残高台帳。外部 Provider 経路と内製 API 経路の未確定差分を同じ場所に予約する。 |
| Provider キャッシュ | Man10BankProvider が同期応答に使うローカルの参照用残高。実体はローカル Vault 台帳で、VaultService が更新・収束させる。 |
| `availableBalance` | ローカル Vault 台帳上で今使ってよい残高。`confirmedBalance + pendingDelta` で計算し、外部 Provider 経路と内製 API 経路の減算可否判定に使う。 |
| 送信待ちキュー | Provider が同期成功させた操作を、後で Man10BankService へ送るために一時保存するキュー。各操作は二重適用を防ぐための `operationId` を持つ。 |
| 唯一の真実 | `Man10BankService` が参照する DB の `user_vault`。Provider キャッシュは真実ではなく従属する参照用データ。 |

---

## 3. 全体アーキテクチャ

```
外部ショップ等
  |
  | Vault API（同期 / メインスレッド）
  v
Vault
  |
  v
Man10BankProvider
  |  1. Provider キャッシュで同期判定
  |  2. 成功分を送信待ちキューへ登録
  v
VaultService（プラグイン側 / 非同期）
  |  - 送信待ちキューの処理
  |  - Man10BankService への REST
  |  - push/再同期の受信
  |  - Provider キャッシュ収束
  v
Man10BankService（C# / Web API）
  |
  v
MySQL user_vault（唯一の真実）
```

内製プラグインと Man10Bank のコマンドは Provider を迂回する。

```
/pay, /deposit, /withdraw, ATM, 内製プラグイン
  |
  v
Man10BankAPI（非同期 API）
  |
  v
VaultService（プラグイン側）
  |
  v
Man10BankService
  |
  v
user_vault / user_bank
```

### 責務境界

| コンポーネント | 責務 | やらないこと |
|---|---|---|
| Man10BankProvider | Vault 互換の同期応答、Provider キャッシュの読み書き、送信待ちキューへ登録できるかの同期判定 | HTTP 待ち、DB 確定待ち、内製コマンド処理 |
| VaultService | Man10BankService との通信、送信待ちキューの処理、内製 API 操作のローカル予約、確定応答処理、push/再同期、Provider キャッシュ収束 | Vault API 互換の同期契約そのもの |
| Man10BankAPI | 内製プラグイン向けの非同期 vault API | Vault の同期 API 互換 |
| Man10BankService | `user_vault` の原子的更新、`vault_log`、冪等制御、`user_bank` との 1 Tx 移動 | Paper メインスレッド都合の吸収 |

---

## 4. 取引経路

### 4.1 外部ショップなど既製 Vault API 経路

対象: ChestShop など、既存の Vault Economy API しか呼べない外部プラグイン。

```
外部ショップ
  -> Vault
  -> Man10BankProvider（同期）
  -> VaultService（非同期）
  -> Man10BankService
```

処理:

1. 外部プラグインが `withdrawPlayer` / `depositPlayer` / `getBalance` / `has` を同期で呼ぶ。
2. Man10BankProvider は Provider キャッシュだけを見て同期的に結果を返す。
3. `withdrawPlayer` / `depositPlayer` が成功した場合、Provider は操作を送信待ちキューに登録する。
4. VaultService が送信待ちキューの操作を順番に処理し、Man10BankService へ冪等キー付きで送信する。
5. Man10BankService の確定結果、または push/再同期により VaultService が Provider キャッシュを収束させる。

この経路の同期成功は「Provider キャッシュ上で取引が成立し、送信待ちキューに登録された」ことを意味する。
「Man10BankService でコミット済み」を意味しない。DB 確定は後段の VaultService が担う。

### 4.2 内製 API 経路

対象:

- `/pay`
- `/deposit`
- `/withdraw`
- ATM
- Man10 系内製プラグイン
- 今後追加する Man10Bank 連携機能

```
プラグイン / コマンド
  -> Man10BankAPI
  -> VaultService
  -> Man10BankService
```

処理の入口:

1. 呼び出し側は非同期 API として VaultService に取引を依頼する。
2. VaultService は、残高を減らす対象 UUID の在席サーバーを確認する。
3. 対象の在席状態に応じて、次のいずれかの経路に分岐する。

#### 対象が自サーバーに在席している場合

1. 自サーバーの VaultService が対象 UUID のローカル Vault 台帳をロックする。
2. 残高を減らす操作なら、Man10BankService へ送る前に未確定差分として予約する。
3. 予約により `availableBalance` が減るため、同時に来た外部 Vault 経路も同じ減算後の残高を見る。
4. VaultService は Man10BankService の確定応答を待つ。
5. 成功時のみ呼び出し側へ成功を返す。
6. 成功時は確定残高で Provider キャッシュを更新し、失敗時は予約を取り消して必要なら権威残高で再同期する。

#### 対象が別 Paper に在席している場合

1. API を受けた Paper の VaultService は、残高を減らす操作を直接 Man10BankService に送らない。
2. Man10BankService の session 情報を使い、対象 UUID の在席サーバーへ処理依頼を転送する。
3. 在席サーバーの VaultService が対象 UUID のローカル Vault 台帳をロックする。
4. 在席サーバーで未確定差分として予約できた場合だけ、Man10BankService へ権威更新を送る。
5. API を受けた Paper は、在席サーバーから返った確定結果を呼び出し側へ返す。
6. 在席サーバーへ到達できない、台帳が `READY` でない、または予約できない場合は失敗を返す。

#### 対象がオフラインの場合

1. 外部 Vault Provider 経路は対象未ロードとして `FAILURE`。
2. 内製 API 経路では、仕様上許可する操作のみ Man10BankService の DB トランザクションで直接処理してよい。
3. 完全オフライン対象への `/pay` や管理操作を許可するかは、コマンド仕様で別途決める。

この経路は Man10BankService のコミット結果を待てるため、Provider の同期成功扱いは使わない。
ただし外部 Vault 経路との二重引き落としを防ぐため、残高を減らす内製操作は必ずローカル Vault 台帳へ先に予約する。
内製コードから `Vault.getProvider(Economy)` を取得して自分自身の Provider を叩く実装は禁止する。

### 4.3 経路選択ルール

| ケース | 経路 |
|---|---|
| 外部ショップなど、Vault API しか使えない既製プラグイン | Vault -> Man10BankProvider |
| Man10Bank の `/pay` | Man10BankAPI -> VaultService |
| Man10Bank の `/deposit` `/withdraw` | Man10BankAPI -> VaultService -> Man10BankService の move API |
| ATM の現金 <-> 電子マネー | Man10BankAPI / VaultService。Vault API は使わない |
| 内製プラグインの電子マネー操作 | Man10BankAPI。Vault API は使わない |
| 管理者の set/edit | Man10BankAPI -> VaultService -> Man10BankService |

---

## 5. 整合性モデル

### 5.1 2 種類の整合性

Vault の同期制約があるため、すべての経路で同じ整合性は提供しない。

| 経路 | 整合性 | 成功の意味 |
|---|---|---|
| 内製 API 経路 | 権威同期（authoritative async） | Man10BankService が `user_vault` をコミットした。 |
| 外部 Vault API 経路 | 同期互換のローカルコミット + 最終収束 | Provider キャッシュで成立し、送信待ちキューに登録された。DB には VaultService が後送する。 |

外部 Vault API 経路では、メインスレッドで HTTP を待てないため、完全な DB 同期コミットは提供しない。
代わりに、Provider キャッシュを「同期取引用の一時台帳」とし、VaultService が唯一の真実へ収束させる。

### 5.2 基本不変条件

1. `user_vault` が最終的な唯一の真実。
2. Provider は HTTP / DB を同期的に待たない。
3. Provider が `SUCCESS` を返した操作は、必ず冪等キー付きで送信待ちキューに載せる。
4. VaultService だけが Man10BankService の vault 書き込み API を呼ぶ。
5. オンラインプレイヤーの電子マネーを減らす操作は、必ず在席サーバーの VaultService がローカル Vault 台帳へ未確定差分を予約してから Man10BankService へ送る。
6. 別 Paper からオンラインプレイヤーの残高を減らす API が呼ばれた場合、その Paper は DB を直接更新せず、Man10BankService 経由で在席サーバーへ処理を転送する。転送できない場合は失敗させる。
7. 同一 UUID のローカル予約、Provider 書き込み、確定反映、予約取消は在席サーバーの VaultService が直列化する。
8. Provider キャッシュが未ロード、古い、競合中、または送信待ちキューが不健康な場合、Provider は新規書き込みを拒否する。
9. VaultService は Man10BankService の確定残高、push、再同期を使って Provider キャッシュを収束させる。

### 5.3 ローカル予約による二重引き落とし防止

外部 Vault 経路と内製 API 経路は、どちらも同じローカル Vault 台帳の `availableBalance` を見る。
`availableBalance` は次の計算値であり、独立して保存する値ではない。

```text
availableBalance = confirmedBalance + pendingDelta
```

そのため、電子マネーを減らす処理は次の順序を必須にする。

1. 対象 UUID のローカル Vault 台帳をロックする。
2. `availableBalance` を確認する。
3. 足りる場合だけ未確定差分を追加し、`availableBalance` を即座に減らす。
4. 外部 Vault 経路はこの時点で `SUCCESS` を返し、送信待ちキューへ登録する。
5. 内製 API 経路はこの予約を保持したまま Man10BankService へ送信し、確定応答を待つ。
6. 成功時は確定残高で予約を消し込む。失敗時は予約を取り消し、必要なら権威残高で再同期する。

例: 残高 100,000 円のプレイヤーが、同時に `/pay 70,000` と外部ショップ購入 70,000 円を行う場合。

- `/pay` が先にローカル予約した場合、`availableBalance` は 30,000 円になる。外部ショップの `withdrawPlayer(70,000)` はキャッシュ不足で `FAILURE`。
- 外部ショップが先にローカル予約した場合、`availableBalance` は 30,000 円になる。`/pay` は VaultService の予約段階で不足として失敗。

この設計では、両方が同時に成功して合計 140,000 円を消費する状態をローカル側で作らない。

### 5.4 別 Paper からの減算操作

別 Paper からオンライン中プレイヤーの残高を減らす操作を直接 Man10BankService に送ると、
在席サーバーの `availableBalance` を事前に減らせない。その状態で在席サーバーの外部ショップが
Provider キャッシュを見て成功すると、DB 側で後から不足が判明する危険がある。

そのため、オンライン中プレイヤーの残高を減らす操作は次のルールにする。

1. API を受けた Paper の VaultService は、Man10BankService に対象 UUID の在席サーバーを確認する。
2. 対象が自サーバーに在席していれば、自サーバーのローカル Vault 台帳で予約してから Man10BankService へ送る。
3. 対象が別 Paper に在席していれば、Man10BankService の WebSocket 経由で在席サーバーの VaultService へ処理依頼を転送する。
4. 在席サーバーの VaultService がローカル Vault 台帳で予約できた場合だけ、Man10BankService へ権威更新を送る。
5. 在席サーバーに到達できない、ローカル Vault 台帳が `READY` でない、または予約できない場合は失敗させる。
6. 対象が完全にオフラインの場合は、Man10BankService が DB トランザクションで直接処理してよい。

このルールにより、外部ショップの同期 `withdrawPlayer` と、別 Paper からの `/pay` 相当の非同期 API は、
最終的に同じ在席サーバーのローカル Vault 台帳で直列化される。

残高を増やす操作は二重引き落としを起こさないため、Man10BankService へ直接送ってよい。
オンライン中の Provider キャッシュには push / 再同期で反映する。ただし push 到着前は一時的に古い低い残高を見て、
外部ショップが保守的に失敗する可能性はある。これは過払いより安全な失敗として許容する。

管理者 `set` のように残高を下げ得る操作は、オンライン中なら同じく在席サーバーへ転送して予約・停止制御を通す。
在席サーバーで処理できない場合は、Provider キャッシュを `STALE` / `DISABLED` にして新規 Vault 書き込みを止めてから実行する。

### 5.5 外部 Vault API 経路の同期保証

Provider は以下を満たす場合だけ書き込み成功を返す。

- 対象プレイヤーの Provider キャッシュが `READY`。
- 対象プレイヤーがこのサーバー上で取引可能な状態。
- 金額が正の整数へ正規化できる。
- `withdraw` の場合、Provider キャッシュ上の `availableBalance >= amount`。
- 送信待ちキューが操作を受理できる。
- VaultService の書き込み健全性が `WRITE_READY`。
- Man10BankService との疎通が正常、または最後の成功確認から許容時間内。
- 未処理件数が閾値以下。

いずれかを満たさない場合は `EconomyResponse` を `FAILURE` にする。
外部ショップへ不確かな成功を返すより、取引を拒否することを優先する。

### 5.6 Man10BankService ダウン時の Provider 挙動

Man10BankProvider は同期メソッド内で Man10BankService へ疎通確認しない。
代わりに VaultService が非同期に health check / WebSocket heartbeat / 直近の送信結果を監視し、
Provider はそのスナップショットだけを見て同期的に成功可否を決める。

書き込み健全性:

| 状態 | Provider 書き込み | 説明 |
|---|---|---|
| `WRITE_READY` | 許可 | Man10BankService への疎通が正常、送信待ちキューが正常、未処理件数が閾値以下。 |
| `DEGRADED` | 原則拒否 | heartbeat 遅延、直近送信失敗、未処理件数増加など。外部 Vault 経路は安全側に倒して `FAILURE`。 |
| `DOWN` | 拒否 | Man10BankService 到達不能。`depositPlayer` / `withdrawPlayer` は即 `FAILURE`。 |
| `DRAINING` | 拒否 | サーバー移動 / shutdown / 復旧処理中。新規 Provider 書き込みは受けない。 |

つまり、Man10BankService が落ちていることを VaultService が検知済みなら、
外部ショップからの `withdrawPlayer` / `depositPlayer` は Provider キャッシュ残高に関係なく `FAILURE` になる。
`getBalance` はキャッシュ値を返してよいが、`has` は書き込み健全性が `WRITE_READY` でない場合 `false` に寄せる。

通常時の Provider 取引は、同期処理内でメモリ上の送信待ちキューへ登録できれば `SUCCESS` を返す。
この時点で `operationId` は必ず発行するが、毎回ディスクへ永続保存することはしない。

Man10BankService の不調を検知した場合は、次の折衷案で扱う。

- ダウン検知後の新規 Provider 書き込みは `FAILURE`。
- すでに外部プラグインへ `SUCCESS` を返した未送信操作だけ、ローカル永続キューへ退避する。
- 保存先は Paper プラグインのデータフォルダ配下に置く（例: SQLite、または追記型ログファイル）。
- 復旧後、永続キューを確認し、同じ `operationId` で送信を再開する。
- Man10BankService で処理済みになった操作は、永続キューから削除するか `COMPLETED` にして後で掃除する。
- 通信失敗なら削除せず再送待ちにする。
- 業務失敗なら削除せず `CONFLICT` / `FAILED` として管理者確認対象にする。
- 長時間復旧しない場合、Provider キャッシュを `STALE` / `DISABLED` にして書き込みを止め、運用アラートを出す。

このため、「ダウン検知済みの新規 Provider 取引」は `FAILURE` にできる。
一方で「`SUCCESS` 返却後にダウンした既存取引」は失敗へ変更できないため、永続キューへ退避して後送する。
ただし、Provider が `SUCCESS` を返した後、VaultService が永続キューへ退避する前に Paper プロセスがクラッシュした場合、
その取引は失われ得る。この折衷案は BankService ダウン対策であり、Paper クラッシュまで含めた完全保証ではない。
内製 API 経路は Man10BankService の確定応答を待つため、サービス到達不能なら呼び出し元へ失敗を返し、
この Provider 用のローカル永続キューには入れない。

### 5.7 成功後に DB 側で失敗した場合

通常は発生させない前提だが、次のような原因で起こり得る。

- Provider キャッシュが真実より高い状態で外部ショップが `withdraw` した。
- 管理操作や他サーバー操作と競合した。
- サーバー移動時の presence / lease が破綻した。
- バグまたは DB 手動変更があった。

外部 Vault API 経路は、外部ショップが既にアイテムを渡した後に失敗を知る可能性がある。
このため、単純なローカル巻き戻しだけでは補償にならない。

方針:

1. VaultService は対象アカウントの Provider 書き込みを `CONFLICT` にして一時停止する。
2. Man10BankService から権威残高を再取得し、Provider キャッシュを上書きする。
3. `severe` ログに `operationId`、uuid、amount、外部経路、失敗理由を構造化して残す。
4. 必要に応じて対象プレイヤー、またはサーバー全体の Vault Provider 書き込みを停止する。
5. 自動で外部ショップの成果物を取り消す処理は行わない。外部プラグイン固有であり汎用補償不能なため。

この状態を運用上の重大不整合として扱う。

### 5.8 サーバー移動と単一アクティブ Provider

同一プレイヤーに対して、同期 Vault 取引を受け付ける Provider キャッシュは同時に 1 つだけにする。

- join 時に VaultService が Man10BankService へ presence / session claim を送る。
- claim 成功後に `user_vault` をロードし、Provider キャッシュを `READY` にする。
- quit / kick / transfer 検知時は新規 Provider 書き込みを止め、送信待ちキューを処理してからキャッシュを破棄する。
- 別サーバーで同じ UUID の claim が来た場合、Man10BankService は後勝ちにして旧 session を失効させる。
- 旧 session からの後続キュー操作は Man10BankService 側で拒否できるよう、書き込みには `sessionId` を含める。

presence / session は Provider キャッシュの健全性を守るための仕組みであり、唯一の真実は引き続き `user_vault`。

---

## 6. Provider キャッシュ

Provider キャッシュは Man10BankProvider が同期応答に使うローカル Vault 台帳の読み取り面。
所有と更新は VaultService 側に寄せ、外部 Provider 経路と内製 API 経路の未確定差分を同じ場所で管理する。

### 6.1 エントリ

```kotlin
data class VaultCacheEntry(
    val uuid: UUID,
    val confirmedBalance: Long,
    val confirmedVersion: Long,
    val pendingDelta: Long,
    val status: Status,
    val sessionId: String,
    val lastSyncedAtMillis: Long,
)
```

概念:

| 値 | 意味 |
|---|---|
| `confirmedBalance` | Man10BankService で確認済みの残高。 |
| `confirmedVersion` | `user_vault.Version`。古い push や再同期結果を捨てるために使う。 |
| `pendingDelta` | Provider 経路または内製 API 経路で予約済みだが、まだ Man10BankService で確定していない差分合計。 |
| `visibleBalance` | `confirmedBalance + pendingDelta`。`getBalance` が返す値。 |
| `availableBalance` | `confirmedBalance + pendingDelta`。外部 Provider 経路と内製 API 経路の `withdraw` / `/pay` / `user_vault -> user_bank` 可否判定に使う計算値。 |
| `status` | `LOADING`（読み込み中）/ `READY`（取引可能）/ `STALE`（古い可能性あり）/ `DRAINING`（キュー処理中）/ `CONFLICT`（競合停止中）/ `DISABLED`（停止中）。 |

残高は内部では `Long`（円）で保持し、Vault 境界でだけ `Double` に変換する。
Provider 書き込みを許可するかどうかは、各エントリの `status` に加えて VaultService 全体の書き込み健全性
（`WRITE_READY` / `DEGRADED` / `DOWN` / `DRAINING`）も見る。

### 6.2 同期 API の挙動

| Vault(Economy) メソッド | Provider の挙動 |
|---|---|
| `getBalance(player)` | `READY` なら `visibleBalance` を返す。未ロード時は `0` を返し、非同期ロードを要求する。 |
| `has(player, amount)` | `READY` かつ書き込み健全性が `WRITE_READY` かつ `availableBalance >= amount`。未ロード・古い可能性がある状態・サービス不健康時は `false`。 |
| `withdrawPlayer(player, amount)` | `READY`、書き込み健全性 `WRITE_READY`、金額正規化成功、残高十分、送信待ちキューへの登録成功なら `pendingDelta -= amount` して `SUCCESS`。それ以外は `FAILURE`。 |
| `depositPlayer(player, amount)` | `READY`、書き込み健全性 `WRITE_READY`、金額正規化成功、送信待ちキューへの登録成功なら `pendingDelta += amount` して `SUCCESS`。それ以外は `FAILURE`。 |
| `hasAccount` / `createPlayerAccount` | Provider は同期では DB 作成を待たない。VaultService に ensure を依頼し、キャッシュがあれば true。 |
| bank 系 API | `hasBankSupport() = false`、bank 系は `NOT_IMPLEMENTED`。 |

### 6.3 送信待ちキューの操作

Provider 書き込み成功時に作る操作:

```kotlin
data class VaultQueuedOperation(
    val operationId: String,
    val sessionId: String,
    val uuid: UUID,
    val type: Type, // PROVIDER_DEPOSIT / PROVIDER_WITHDRAW
    val amount: Long,
    val pluginName: String?,
    val reason: String?,
    val createdAtMillis: Long,
)
```

要件:

- `operationId` は UUID などの一意な冪等キー。同じ操作を二重適用しないために使う。
- Man10BankService 側で `operationId` を UNIQUE にし、重複送信を同じ結果として扱う。
- Provider は送信待ちキューへの登録に失敗した操作を `SUCCESS` にしてはならない。
- 通常時はメモリ上の送信待ちキューへ登録できれば `SUCCESS` を返す。
- 永続キューの対象は、Provider が外部プラグインへ `SUCCESS` を返した後、まだ Man10BankService へ確定送信できていない外部 Vault 取引のみ。
- Man10BankService の不調を検知したら、未送信のメモリキューをプラグインデータフォルダ配下の SQLite または追記型ログファイルへ退避する。
- 復旧後、永続キューを同じ `operationId` で処理し、成功した操作は削除または `COMPLETED` 化する。
- 送信待ちキューが詰まった場合、Provider キャッシュを `STALE` または `DISABLED` にして新規書き込みを止める。
- Paper クラッシュ時にメモリキューから永続キューへ退避できていない操作は失われ得る。これは本折衷案の既知リスクとして扱う。

### 6.4 収束

VaultService は次のイベントで Provider キャッシュを更新する。

| イベント | 更新内容 |
|---|---|
| 初回ロード / join claim 成功 | `confirmedBalance` / `confirmedVersion` をセットし、`READY` にする。 |
| キュー操作の確定成功 | 対応する Provider 未確定操作を外し、Man10BankService の返した確定残高・version を反映する。未確定操作が残る場合は `pendingDelta` を再計算する。 |
| 内製 API 操作の確定成功 | 対応する内製 API の予約を外し、Man10BankService の返した確定残高・version を反映する。 |
| 内製 API 操作の失敗 | 対応する予約を取り消し、必要なら Man10BankService から権威残高を再取得する。 |
| Man10BankService からの push | `version` が新しければ `confirmedBalance` / `confirmedVersion` を更新し、未確定操作を再適用して `visibleBalance` を計算する。 |
| 定期再同期 | 権威残高を再取得し、同様に未確定操作を再適用する。 |
| 競合検知 | `CONFLICT` にして新規 Provider 書き込みを止め、手動確認できるログを残す。 |

古い push は `version` で捨てる。
未確定操作は `operationId` で管理し、HTTP タイムアウトや WebSocket 切断後も二重適用しない。

---

## 7. VaultService

プラグイン側の VaultService は電子マネー操作の単一窓口。
外部 Vault 互換経路と内製 API 経路の両方を受けるが、同期応答を返すのは Provider だけ。

### 7.1 主な責務

1. Provider 送信待ちキューの処理。
2. Man10BankService の `/api/Vault/*` 呼び出し。
3. `Man10BankAPI` からの非同期取引を実行し、確定結果を返す。
4. 別 Paper から転送されたオンラインプレイヤー減算依頼を、自サーバー在席者のローカル Vault 台帳で予約して実行する。
5. join / quit / session claim。
6. Man10BankService からの push 受信、または定期再同期。
7. health check / WebSocket heartbeat / 直近送信結果による書き込み健全性の管理。
8. Provider キャッシュの `READY` / `STALE` / `CONFLICT` 管理。
9. Provider 書き込みを受け付けてよいかの健全性判定。

### 7.2 非同期 API

Man10BankAPI / コマンド / 内製プラグインは以下のような非同期メソッドだけを使う。

| メソッド | 用途 |
|---|---|
| `getBalance(uuid)` | Man10BankService から権威残高を取得。オンラインなら Provider キャッシュも更新。 |
| `deposit(uuid, amount, reason)` | 電子マネーを権威入金。 |
| `withdraw(uuid, amount, reason)` | 電子マネーを権威出金。オンラインなら在席サーバーで先にローカル予約し、不足時はサービスへ送らず失敗。 |
| `transfer(from, to, amount, reason)` | `/pay`。送金元を在席サーバーで先にローカル予約し、Man10BankService の 1 Tx で from/to を更新。 |
| `moveVaultToBank(uuid, amount, reason)` | `/deposit`。vault 側を在席サーバーで先にローカル予約し、`user_vault -> user_bank` を 1 Tx で移動。 |
| `moveBankToVault(uuid, amount, reason)` | `/withdraw`。`user_bank -> user_vault` を 1 Tx で移動。 |
| `setBalance(uuid, amount, reason)` | 管理者操作。 |

これらは Man10BankService の確定応答を待つ。
成功後、VaultService は Provider キャッシュが存在する UUID について確定残高を反映する。

残高を減らす操作（`withdraw`、`transfer` の送金元、`moveVaultToBank`）は、Man10BankService へ送る前に
在席サーバーのローカル Vault 台帳で予約する。予約できない場合は、その時点で不足として失敗させる。
API を受けた Paper と在席サーバーが違う場合は、Man10BankService を介して在席サーバーへ処理を転送する。
残高を増やす操作（`deposit`、`transfer` の受取人、`moveBankToVault`）は、対象が同一サーバーでオンラインなら表示・同期のために正の未確定差分として反映してよい。

### 7.3 Provider 送信待ちキューの処理

処理の流れ:

1. Provider が送信待ちキューに操作を追加する。
2. VaultService が登録順に操作を取得する。
3. Man10BankService へ `operationId` / `sessionId` / `uuid` / `amount` を送る。
4. 成功なら未確定操作から外し、返却された `balance` / `version` を Provider キャッシュへ反映する。
5. タイムアウトなら同じ `operationId` で再送する。
6. Man10BankService 不調を検知したら、未送信操作を永続キューへ退避し、新規 Provider 書き込みを止める。
7. 復旧後、永続キューを同じ `operationId` で再送し、成功した操作を削除または `COMPLETED` 化する。
8. 業務失敗なら `CONFLICT` として扱う。

POST は通常リトライしない方針だが、Provider のキュー操作は冪等キーが必須なので、
同一 `operationId` に限って再送できる。

### 7.4 push / 再同期

推奨は WebSocket による push。

```
Man10BankService -> VaultService -> Provider キャッシュ
```

イベント例:

```jsonc
{
  "type": "vault.balance",
  "uuid": "0a1b...-....",
  "balance": 123450,
  "version": 42,
  "operationId": "optional",
  "cause": "PROVIDER_WITHDRAW|PAY|MOVE|SET",
  "ts": "2026-06-17T10:00:00Z"
}
```

WebSocket が使えない期間は定期再同期で補う。
再接続時はオンラインプレイヤー全員を全件再同期する。

---

## 8. Man10BankService API

### 8.1 VaultController

`/api/Vault` を追加する。書き込みは `RequireWriteScope`。

| メソッド | 概要 | 返却 |
|---|---|---|
| `GET {uuid}/balance` | 電子マネー残高取得 | `{ balance, version }` |
| `GET {uuid}/logs` | 電子マネー取引ログ | `VaultLog[]` |
| `POST deposit` | 権威入金。内製 API と Provider 送信待ちキューの両方で使う。 | `{ balance, version }` |
| `POST withdraw` | 権威出金。不足時は `409`。 | `{ balance, version }` |
| `POST transfer` | 電子マネー -> 電子マネー。`/pay` 用。 | from/to の残高・version |
| `POST move` | `user_vault` と `user_bank` の相互移動。`/deposit` `/withdraw` / ATM 用。 | vault/bank の残高 |
| `POST set` | 管理者用の絶対値設定。 | `{ balance, version }` |
| `POST session/claim` | Paper サーバーの player session claim。 | `{ sessionId, balance, version }` |
| `POST session/release` | session release。 | `204` |
| `POST route/debit` | オンライン中プレイヤーの減算依頼を在席サーバーへ転送する。呼び出し元 Paper は直接 DB 更新しない。 | 転送先の確定結果 |
| `GET session/{uuid}` | 対象 UUID の在席サーバー / session 状態を取得する。 | `{ server, sessionId, online }` |
| `GET ws` | push / presence / session 失効通知。 | WebSocket |

### 8.2 書き込み共通要件

- すべての書き込みは DB トランザクション内で行う。
- `user_vault` の対象行をロックし、残高不足や負数をサービス側で拒否する。
- 成功時に `version++`。
- `vault_log` に記録する。
- `operationId` が指定された場合は UNIQUE とし、同じ `operationId` の再送には同じ結果を返す。
- `sessionId` が指定された Provider キュー操作は、現在の claim と一致しなければ拒否する。
- コミット後に push を発行する。
- オンライン中プレイヤーの残高を減らすリクエストが在席サーバー以外から来た場合、直接 DB 更新せず在席サーバーへ転送する。転送できない場合は失敗にする。

---

## 9. データモデル

### 9.1 `user_vault`

名称と基本形は維持する。`user_bank` とは分離する。

```csharp
public class UserVault
{
    public int Id { get; set; }
    [StringLength(16)] public required string Player { get; set; }
    [StringLength(36)] public required string Uuid { get; set; }   // UNIQUE
    public decimal Balance { get; set; }
    public long Version { get; set; }
}
```

```sql
create table user_vault (
    id      int auto_increment primary key,
    player  varchar(16)   not null,
    uuid    varchar(36)   not null,
    balance decimal(20)   not null default 0,
    version bigint        not null default 0,
    unique key uq_user_vault_uuid (uuid)
);
```

### 9.2 `vault_log`

電子マネー専用ログ。銀行の `money_log` とは混ぜない。

追加で持つべき項目:

| 項目 | 用途 |
|---|---|
| `operation_id` | Provider 送信待ちキュー / Man10BankAPI の冪等キー。nullable でもよいが、指定時は UNIQUE。 |
| `source` | `PROVIDER` / `MAN10_API` / `ADMIN` / `SYSTEM`。 |
| `server` | 操作元 Paper サーバー名。 |
| `session_id` | Provider キュー操作の場合の session。 |
| `balance_after` | 操作後残高。監査と重複応答用。 |

### 9.3 session / presence

Provider キャッシュの単一アクティブ性を守るため、Man10BankService は短命の session を管理する。
DB 永続テーブルにするか、プロセス内メモリ + 再接続時の全件再同期にするかは実装時に選ぶ。

最低限必要な情報:

| 項目 | 用途 |
|---|---|
| `uuid` | 対象プレイヤー。 |
| `server` | claim した Paper サーバー。 |
| `session_id` | Provider キュー操作に付与する識別子。 |
| `expires_at` | ハートビート切れで失効させるための期限。 |

---

## 10. プラグイン側コンポーネント

| コンポーネント | 役割 |
|---|---|
| `economy/Man10BankProvider.kt` | `Economy` 実装。外部 Vault Consumer から呼ばれる同期互換レイヤ。 |
| `service/vault/VaultService.kt` | プラグイン側の非同期 vault サービス。Man10BankService への唯一の書き込み窓口。 |
| `service/vault/VaultProviderCache.kt` | Provider が読む同期キャッシュ。VaultService が収束更新する。 |
| `service/vault/VaultWriteQueue.kt` | Provider 成功操作の送信待ちキュー。冪等キーを管理する。 |
| `service/vault/VaultSyncClient.kt` | WebSocket push / session / presence / 再同期 / 別 Paper からの処理依頼受信。 |
| `api/VaultApiClient.kt` | `/api/Vault/*` REST クライアント。 |
| `Man10BankAPI.kt` | 内製プラグイン向けの非同期公開 API。 |
| `listener/VaultLifecycleListener.kt` | join/quit で claim、load、キュー処理、release を行う。 |

### 10.1 Economy 登録

```kotlin
server.servicesManager.register(
    Economy::class.java,
    man10BankProvider,
    this,
    ServicePriority.High
)
```

要件:

- `plugin.yml` は Vault より後にロードされるよう `softdepend: [Vault]` を維持する。
- 登録後に `ServicesManager.getRegistration(Economy)` の実効 Provider が自分自身か確認する。
- 競合 Provider が実効になった場合は `severe` ログを出し、Vault Provider 機能を停止する。
- 段階導入用に `vault.providerEnabled` で登録を切り替え可能にする。

### 10.2 スレッドモデル

| 処理 | スレッド |
|---|---|
| Man10BankProvider の `getBalance` / `has` / `withdrawPlayer` / `depositPlayer` | メインスレッド同期。HTTP 待ちはしない。 |
| Provider キャッシュ操作 | lock-free / synchronized などで短時間に完了する同期処理。 |
| 送信待ちキューの処理 | `Dispatchers.IO`。 |
| Man10BankService REST | `Dispatchers.IO`。 |
| WebSocket push / 再同期 | `Dispatchers.IO`。 |
| コマンド結果のプレイヤー通知 | 必要に応じてメインスレッドへ戻す。 |

---

## 11. 既存処理への影響

### 11.1 `VaultManager` の置換

既存の [`VaultManager`](../src/main/java/red/man10/man10bank/service/VaultManager.kt) は外部 Economy Provider を取得する Consumer。
新設計では Man10Bank 自身が Provider になるため、`VaultManager` は外部 Economy Consumer ではなく
`VaultService` のファサードに作り替える。

方針:

- `VaultManager` 名は残し、既存の呼び出し側改修を最小化する。
- `hook()` / `provider()` のような外部 Economy Provider 取得前提の API は廃止または互換用 no-op にする。
- `getBalance` / `deposit` / `withdraw` / `isAvailable` は `VaultService` へ委譲する。
- 内製の `/deposit` `/withdraw` `/pay` / ATM は、最終的には `VaultService` / `Man10BankAPI` の非同期 API を使う。
- `VaultManager` から `ServicesManager.getRegistration(Economy)` で自分自身の Provider を取得して呼ぶ実装は禁止する。

### 11.2 `/deposit` `/withdraw`

現在は「Vault から引き落とし -> Bank API -> 失敗時補償」の Saga になっている。
新設計では `Man10BankService` の `POST /api/Vault/move` へ委譲し、
`user_vault` と `user_bank` を 1 DB トランザクションで更新する。

- `/deposit`: `user_vault -> user_bank`
- `/withdraw`: `user_bank -> user_vault`

クライアント側の補償ロジックは削除する。

### 11.3 `/pay`

電子マネー送金 `/pay` は `Man10BankAPI -> VaultService -> Man10BankService transfer`。
Provider は経由しない。
ただし送金元の電子マネーは、Man10BankService へ送る前に在席サーバーのローカル Vault 台帳で予約する。
これにより同時に外部ショップ購入が来ても、送金元残高を二重に使わない。

送金先が別サーバーまたはオフラインでも、サービス側で許可する仕様にするかはコマンド仕様で決める。
どちらの場合も唯一の真実は Man10BankService で、オンライン中の Provider キャッシュは push/再同期で収束する。

### 11.4 ATM

ATM の現金 <-> 電子マネー変換は Vault API を呼ばず、VaultService の非同期 API を使う。
アイテム操作との順序は既存 UI/サービス側で制御し、電子マネー側は Man10BankService の確定結果を待つ。

### 11.5 残高表示

`BalanceRegistry` の `id = "vault"` は維持する。
表示値は原則 Provider キャッシュ由来とし、キャッシュがない場合は VaultService に非同期ロードを依頼する。

---

## 12. 金額・型の扱い

- DB / サービス内部 / Provider キャッシュは整数円を `Long` または `decimal(20)` で扱う。
- Vault(Economy) の境界のみ `Double`。
- `fractionalDigits() = 0`。
- 小数は既存方針に合わせて切り捨て、または不正として拒否する。実装時に統一する。
- 0 以下、NaN、Infinity、上限超過は拒否する。
- `double` 同士の累積演算で残高を保持しない。

---

## 13. 障害・エッジケース

| ケース | 方針 |
|---|---|
| Provider キャッシュ未ロード | 書き込みは `FAILURE`。読みは `0` / `false` を返し、非同期ロードを要求する。 |
| 外部ショップ購入と内製 `/pay` が同時に残高を減らす | 両方が同じローカル Vault 台帳へ先に予約する。先に予約した方が `availableBalance` を減らすため、合計額が残高を超える場合は後続がローカル不足で失敗する。 |
| 別 Paper からオンライン中プレイヤーの残高を減らす | API を受けた Paper は直接 DB 更新しない。Man10BankService 経由で在席サーバーへ転送し、在席サーバーのローカル Vault 台帳で予約できた場合だけ実行する。 |
| 別 Paper からオンライン中プレイヤーの残高を増やす | 直接 Man10BankService で権威更新してよい。Provider キャッシュには push/再同期で反映する。push 前の一時的な低い残高による失敗は許容する。 |
| Man10BankService 到達不能 | 書き込み健全性を `DOWN` / `DEGRADED` にし、Provider の `depositPlayer` / `withdrawPlayer` / `has` は新規 `FAILURE` / `false`。成功返却済みで未送信の Provider 操作は永続キューへ退避し、復旧後に同じ `operationId` で再送する。 |
| 送信待ちキューの未処理件数過多 | Provider 書き込みを止める。外部ショップには `FAILURE` を返す。 |
| Provider 成功後の service 失敗 | `CONFLICT`。自動補償せず、権威残高で収束し、重大ログを残す。 |
| HTTP タイムアウト | Provider キュー操作は同一 `operationId` で再送。内製 API は結果不明として呼び出し元へ失敗または再確認を返す。 |
| WebSocket 切断 | 定期再同期に落とす。再接続後にオンライン全員を全件再同期。 |
| サーバークラッシュ | `user_vault` が真実。永続キューへ退避済みの操作は再起動後に同じ `operationId` で再送する。メモリキューにしか無かった Provider 成功返却済み操作は失われ得るため、既知リスクとして運用ログ・監視対象にする。 |
| 二重 Provider 登録 | 実効 Provider が自分でなければ Provider 機能を停止し、severe ログ。 |
| session mismatch | Man10BankService が Provider キュー操作を拒否し、VaultService は `CONFLICT` にする。 |
| 管理者 set | Man10BankService で権威更新し、push/再同期で Provider キャッシュを収束。 |

---

## 14. セキュリティ

- REST 書き込みは既存の `RequireWriteScope` を使う。
- WebSocket / session claim も Bearer 認証を必須にする。
- Paper -> Man10BankService の API キーは `config.yml` / 環境変数管理とし、コミットしない。
- `serverName` / `sessionId` はクライアント自己申告だけを信用せず、認証情報またはサーバー登録情報と紐づける。
- `operationId`、`sessionId`、`serverName`、`source` は監査ログに残す。
