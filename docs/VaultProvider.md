# VaultProvider 設計書

Man10Bank を **Vault(Economy) の Provider** 化し、プレイヤーの電子マネーを
`Man10BankService`（C# / MySQL）の `user_vault` で一元管理する。

本設計では、外部ショップなどが使う既存 Vault API の **同期制約** と、
Man10BankService を唯一の真実（source of truth）にする **非同期・権威更新** を分離して扱う。

- 対象プラグイン: `[src/main/java/red/man10/man10bank](../src/main/java/red/man10/man10bank)`
- 対象サービス: `[man10bankservice/Man10BankService](../../man10bankservice/Man10BankService)`
- 関連: `[BankAPI.md](./BankAPI.md)`（既存の Bank=銀行残高 API 仕様）

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
- [15. 既知のリスク](#15-既知のリスク)

---

## 1. 方針

現状の Man10Bank は Vault の Consumer として外部 Economy Provider を呼んでいる。
本設計では向きを反転し、Man10Bank が `net.milkbowl.vault.economy.Economy` を実装して
Vault Provider になる。

### 確定方針


| 論点                 | 方針                                                                                                                               |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------- |
| 電子マネーの実体           | `Man10BankService` の `user_vault` を唯一の真実（source of truth）にする。                                                                    |
| Vault Provider の制約 | Milkbowl/Vault 経由の Economy 操作は同期 API。メインスレッド呼び出しはその場で処理し、off-main 呼び出しはメインスレッドへ同期ディスパッチする。Provider 内で HTTP を待たない。                |
| 外部ショップ経路           | 外部ショップなど既製 Vault API しか使えないプラグインだけが `Man10BankProvider` を通る。同期応答は Provider キャッシュで成立させる。                                          |
| 内製経路               | `/pay`、`/deposit`、`/withdraw`、ATM、Man10 系内製プラグインは Vault API を直接呼ばず、`Man10BankAPI -> VaultService -> Man10BankService` の非同期経路を使う。 |
| 送金コマンド             | `/pay` は同一 Paper 上でオンラインのプレイヤー間の電子マネー送金、`/mpay` は Bank 残高間の送金に固定する。両者の資産種別を混在させない。                                               |
| 収束責務               | `Man10BankProvider` は同期互換用のキャッシュを持つ。`VaultService` が Man10BankService との通信、確定応答、push、再同期を通じて Provider キャッシュを真実へ収束させる。            |
| 対応 API 範囲          | 旧 Vault Economy（単一通貨・`double`）のみ。VaultUnlocked / 多通貨は対象外。                                                                        |
| 金額規則               | 小数金額は整数円へ切り捨てる。残高上限は Man10BankService の設定値を権威とし、既定値は 1 兆円。                                                                       |
| 既存電子マネー移行          | 別タスク。`user_vault` は初期値 0 を許容する。                                                                                                  |


---

## 2. 用語


| 用語                 | 意味                                                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| 電子マネー              | プレイヤーが直接使える残高。Vault(Economy) の `getBalance` が返す値。DB 上は `user_vault`。                                                   |
| 銀行残高               | `user_bank.Balance`。`/deposit` `/withdraw` で電子マネーと相互移動する既存の銀行残高。                                                       |
| Man10BankProvider  | `Economy` 実装。外部 Vault Consumer から同期で呼ばれる互換レイヤ。                                                                         |
| VaultService       | プラグイン側の非同期サービス。Man10BankService への全 vault 書き込み、送信待ちキュー、再同期、Provider キャッシュ収束を担当する。                                      |
| Man10BankAPI       | 内製プラグイン向けの公開 API。Vault API ではなく VaultService を呼ぶ。                                                                      |
| 在席サーバー             | 対象プレイヤーが現在ログインしている Paper サーバー。Provider キャッシュの操作可否は各 Paper の Bukkit オンライン状態で判断する。                                       |
| ローカル Vault 台帳      | VaultService が管理するオンラインプレイヤーのローカル残高台帳。外部 Provider 経路と内製 API 経路の未確定差分を同じ場所に予約する。                                        |
| Provider キャッシュ     | Man10BankProvider が同期応答に使うローカルの参照用残高。実体はローカル Vault 台帳で、VaultService が更新・収束させる。                                         |
| `WARMING_UP`       | join 直後の Provider キャッシュ状態。旧サーバーの送信待ちキューが反映される猶予を置くため、金銭操作は拒否し、クールタイム後に権威残高を再取得して `READY` にする。                          |
| `availableBalance` | ローカル Vault 台帳上で今使ってよい残高。`confirmedBalance + pendingDelta` で計算する。`pendingDelta` は `operationId` ごとの未確定減算予約から計算する 0 以下の値とし、DB 未確定の入金は含めない。 |
| 送信待ちキュー            | Provider が同期成功させた操作を、後で Man10BankService へ送るために一時保存するキュー。各操作は二重適用を防ぐための `operationId` を持つ。                             |
| 唯一の真実              | `Man10BankService` が参照する DB の `user_vault`。Provider キャッシュは真実ではなく従属する参照用データ。                                            |


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


| コンポーネント           | 責務                                                                                 | やらないこと                   |
| ----------------- | ---------------------------------------------------------------------------------- | ------------------------ |
| Man10BankProvider | Vault 互換の同期応答、Provider キャッシュの読み書き、送信待ちキューへ登録できるかの同期判定                              | HTTP 待ち、DB 確定待ち、内製コマンド処理 |
| VaultService      | Man10BankService との通信、送信待ちキューの処理、内製 API 操作のローカル予約、確定応答処理、push/再同期、Provider キャッシュ収束 | Vault API 互換の同期契約そのもの    |
| Man10BankAPI      | 内製プラグイン向けの非同期 vault API                                                            | Vault の同期 API 互換         |
| Man10BankService  | `user_vault` の原子的更新、`vault_log`、冪等制御、`user_bank` との 1 Tx 移動                        | Paper メインスレッド都合の吸収       |


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
`depositPlayer` が `SUCCESS` を返しても、DB 確定前の入金額は Provider キャッシュ、`visibleBalance`、
`availableBalance` のいずれにも加算しない。Man10BankService の更新完了後に初めて確定残高として反映する。

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
2. VaultService は、対象 UUID が自サーバーの Bukkit オンラインプレイヤーかを確認する。
3. 対象が自サーバーにいるかどうかと Provider キャッシュ状態に応じて、次のいずれかの経路に分岐する。

ここでいう「残高を減らす」「残高を増やす」は、電子マネーである `user_vault` の増減を基準にする。
Man10Bank コマンド名では、`/deposit` は `user_vault -> user_bank` なので電子マネーを減らす操作、
`/withdraw` は `user_bank -> user_vault` なので電子マネーを増やす操作として扱う。
複数プレイヤーを扱う `/pay` はこの単独 UUID の分岐とは別に、送金元と送金先の両方が
同一の自サーバーでオンラインかつ Provider キャッシュが `READY` の場合だけ実行する。

#### 対象が自サーバーに在席している場合

1. Provider キャッシュが `WARMING_UP` / `LOADING` / `STALE` / `DRAINING` / `CONFLICT` なら、金銭操作は拒否する。
2. Provider キャッシュが `READY` の場合だけ、自サーバーの VaultService が対象 UUID のローカル Vault 台帳をロックする。
3. 残高を減らす操作なら、Man10BankService へ送る前に未確定差分として予約する。
4. 予約により `availableBalance` が減るため、同時に来た外部 Vault 経路も同じ減算後の残高を見る。
5. VaultService は Man10BankService の確定応答を待つ。
6. 成功時のみ呼び出し側へ成功を返す。
7. 成功時は確定残高で Provider キャッシュを更新し、失敗時は予約を取り消して必要なら権威残高で再同期する。

#### 対象が自サーバーにいない場合

1. 外部 Vault Provider 経路は対象未ロードとして `FAILURE`。
2. 通常の内製 API 経路では、対象が別 Paper にいるか完全オフラインかを問わず、単独の `deposit(uuid, amount, reason)` だけ許可する。
3. この `deposit` は `user_vault` を増やす単独 API を指す。Man10Bank コマンドの `/deposit` ではない。
4. `/deposit` `/withdraw`、`/pay` の送金元・送金先、`withdraw`、`transfer`、`move` は失敗させる。
5. 管理者 `setBalance` / `editvault` は例外として対象の在席状況を問わず Man10BankService へ送る。
6. 自サーバーにいないプレイヤー向けのその他の資産操作は、既存実装済みの Bank 機能を使う。

この経路は Man10BankService のコミット結果を待てるため、Provider の同期成功扱いは使わない。
ただし外部 Vault 経路との二重引き落としを防ぐため、残高を減らす内製操作は必ずローカル Vault 台帳へ先に予約する。
内製コードから `Vault.getProvider(Economy)` を取得して自分自身の Provider を叩く実装は禁止する。

### 4.3 経路選択ルール


| ケース                                | 経路                                                                    |
| ---------------------------------- | --------------------------------------------------------------------- |
| 外部ショップなど、Vault API しか使えない既製プラグイン   | Vault -> Man10BankProvider                                            |
| Man10Bank の `/pay`                 | 同一 Paper 上でオンラインのプレイヤー間だけ、Man10BankAPI -> VaultService。電子マネー -> 電子マネー |
| Man10Bank の `/mpay`                | 既存 BankService。Bank -> Bank。VaultService の対象外                         |
| Man10Bank の `/deposit` `/withdraw` | Man10BankAPI -> VaultService -> Man10BankService の move API           |
| ATM の現金 <-> 電子マネー                  | Man10BankAPI / VaultService。Vault API は使わない                           |
| 内製プラグインの電子マネー操作                    | Man10BankAPI。Vault API は使わない                                          |
| 管理者の set/edit                      | Man10BankAPI -> VaultService -> Man10BankService                      |


---

## 5. 整合性モデル

### 5.1 2 種類の整合性

Vault の同期制約があるため、すべての経路で同じ整合性は提供しない。


| 経路              | 整合性                       | 成功の意味                                                      |
| --------------- | ------------------------- | ---------------------------------------------------------- |
| 内製 API 経路       | 権威同期（authoritative async） | Man10BankService が `user_vault` をコミットした。                   |
| 外部 Vault API 経路 | 同期互換のローカルコミット + 最終収束      | Provider キャッシュで成立し、送信待ちキューに登録された。DB には VaultService が後送する。 |


外部 Vault API 経路では、メインスレッドで HTTP を待てないため、完全な DB 同期コミットは提供しない。
代わりに、Provider キャッシュを「同期取引用の一時台帳」とし、VaultService が唯一の真実へ収束させる。

### 5.2 基本不変条件

1. `user_vault` が最終的な唯一の真実。
2. Provider は HTTP / DB を同期的に待たない。
3. Provider が `SUCCESS` を返した操作は、必ず冪等キー付きで送信待ちキューに載せる。
4. VaultService だけが Man10BankService の vault 書き込み API を呼ぶ。
5. オンラインプレイヤーの電子マネーを減らす操作は、対象が自サーバーに在席し、Provider キャッシュが `READY` の場合だけ許可し、ローカル Vault 台帳へ未確定差分を予約してから Man10BankService へ送る。
6. 自サーバーにいないプレイヤーの残高を減らす API は拒否する。
7. 自サーバーにいないプレイヤーの電子マネー操作は、単独の `deposit` と管理者 `setBalance` / `editvault` だけ許可する。それ以外は拒否し、必要なら既存 Bank 機能の対象にする。
8. join 直後は Provider キャッシュを `WARMING_UP` にし、クールタイム後に権威残高を再取得してから `READY` にする。
9. 同一 UUID のローカル予約、Provider 書き込み、確定反映、予約取消は在席サーバーの VaultService が直列化する。
10. Provider キャッシュが未ロード、古い、競合中、または送信待ちキューが不健康な場合、Provider は新規書き込みを拒否する。
11. VaultService は Man10BankService の確定残高、push、再同期を使って Provider キャッシュを収束させる。
12. 正の未確定差分は Provider キャッシュへ反映しない。入金は Man10BankService の DB 更新完了後にだけ `confirmedBalance`、`visibleBalance`、`availableBalance` を増やす。
13. `/pay` は送金元と送金先が同一 Paper 上でオンラインの場合だけ許可し、電子マネー以外の資産へは移動しない。Bank 間送金は `/mpay` が担う。

### 5.3 ローカル予約による二重引き落とし防止

外部 Vault 経路と内製 API 経路は、どちらも同じローカル Vault 台帳の `availableBalance` を見る。
`availableBalance` は次の計算値であり、独立して保存する値ではない。

```text
availableBalance = confirmedBalance + pendingDelta
```

`pendingDelta` は `operationId` ごとの未確定減算予約を合計した 0 以下の計算値とする。
Provider の `depositPlayer` や内製 API の入金は、送信中であっても正の `pendingDelta` を作らない。
したがって DB 未確定の入金を出金、`/pay`、`user_vault -> user_bank` に再利用できない。

そのため、電子マネーを減らす処理は次の順序を必須にする。

1. 対象 UUID のローカル Vault 台帳をロックする。
2. `availableBalance` を確認する。
3. 足りる場合だけ `operationId` ごとの未確定減算予約を追加し、`availableBalance` を即座に減らす。
4. 外部 Vault 経路はこの時点で `SUCCESS` を返し、送信待ちキューへ登録する。
5. 内製 API 経路はこの予約を保持したまま Man10BankService へ送信し、確定応答を待つ。
6. 成功時は確定残高で予約を消し込む。失敗時は予約を取り消し、必要なら権威残高で再同期する。

例: 残高 100,000 円のプレイヤーが、同時に `/pay 70,000` と外部ショップ購入 70,000 円を行う場合。

- `/pay` が先にローカル予約した場合、`availableBalance` は 30,000 円になる。外部ショップの `withdrawPlayer(70,000)` はキャッシュ不足で `FAILURE`。
- 外部ショップが先にローカル予約した場合、`availableBalance` は 30,000 円になる。`/pay` は VaultService の予約段階で不足として失敗。

この設計では、両方が同時に成功して合計 140,000 円を消費する状態をローカル側で作らない。

### 5.4 自サーバーにいないプレイヤーへの操作

自サーバーにいないプレイヤーの残高を減らす操作を直接 Man10BankService に送ると、
そのプレイヤーが別 Paper にいる場合に、在席サーバーの `availableBalance` を事前に減らせない。
その状態で在席サーバーの外部ショップが Provider キャッシュを見て成功すると、
DB 側で後から不足が判明する危険がある。

在席サーバー側で減算を実行させる仕組みや Service 側の短命な所有権トークンを作れば防げるが、実装が重くなる。
本設計では簡素化のため、自サーバーにいないプレイヤーに対する減算操作を拒否する。

自サーバーにいないプレイヤーへの操作ルール:


| 操作                                  | 方針                         | 理由                                              |
| ----------------------------------- | -------------------------- | ----------------------------------------------- |
| 単独の `deposit(uuid, amount, reason)` | 許可。Man10BankService へ直接送る。 | Provider キャッシュは一時的に stale-low になるだけで、過払いは起きない。  |
| `user_vault` を減らす操作                 | 拒否。                        | Provider キャッシュが stale-high になり、外部ショップで過払いが起き得る。 |
| `transfer` / `move`                  | 拒否。                        | 複数資産・送金はローカル台帳との整合条件が複雑になるため。                       |
| 管理者 `setBalance` / `editvault`     | 許可。                        | 管理者操作は例外として権威更新を優先し、衝突時はコマンド結果として返して運用対応する。        |


オンライン中の Provider キャッシュには push / 再同期で反映する。ただし push 到着前は一時的に古い低い残高を見て、
外部ショップが保守的に失敗する可能性はある。これは過払いより安全な失敗として許容する。

### 5.5 外部 Vault API 経路の同期保証

Provider は以下を満たす場合だけ書き込み成功を返す。

- 対象プレイヤーの Provider キャッシュが `READY`。
- 対象プレイヤーがこのサーバー上で取引可能な状態。
- join 直後の `WARMING_UP` ではない。
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


| 状態            | Provider 書き込み | 説明                                                                     |
| ------------- | ------------- | ---------------------------------------------------------------------- |
| `WRITE_READY` | 許可            | Man10BankService への疎通が正常、送信待ちキューが正常、未処理件数が閾値以下。                        |
| `DEGRADED`    | 原則拒否          | heartbeat 遅延、直近送信失敗、未処理件数増加など。外部 Vault 経路は安全側に倒して `FAILURE`。           |
| `DOWN`        | 拒否            | Man10BankService 到達不能。`depositPlayer` / `withdrawPlayer` は即 `FAILURE`。 |
| `DRAINING`    | 拒否            | サーバー移動 / shutdown / 復旧処理中。新規 Provider 書き込みは受けない。                       |


つまり、Man10BankService が落ちていることを VaultService が検知済みなら、
外部ショップからの `withdrawPlayer` / `depositPlayer` は Provider キャッシュ残高に関係なく `FAILURE` になる。
`getBalance` はキャッシュ値を返してよいが、`has` は書き込み健全性が `WRITE_READY` でない場合 `false` に寄せる。
この拒否は `isEnabled()` の返値に依存させず、各取引メソッドが必ず書き込み健全性を検査して実施する。
`isEnabled()` は Provider が登録済みかつ `vault.providerEnabled = true` であることだけを表し、
`DEGRADED` / `DOWN` / `DRAINING` の一時状態では `true` を維持する。これにより、外部プラグインが
一時障害を恒久的な Provider 無効化と判断して接続を破棄することを避ける。復旧後は再登録なしで取引を再開する。

管理者設定またはプラグイン停止により Provider 自体を無効化する場合だけ `isEnabled() = false` とし、
ServicesManager から登録解除する。登録解除前の参照を保持する外部プラグインに備え、この状態では
`getBalance` は `0`、`has` / `hasAccount` / `createPlayerAccount` は `false`、入出金は `FAILURE` を返し、新規操作を受理しない。

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
- 永続キューへの退避失敗、キューの `CONFLICT` / `FAILED` 化、正常 shutdown 時の未退避操作など、検知可能な異常は `severe` ログへ構造化して出力する。
- Paper の強制終了では消失したメモリ上の操作自体を再起動後に特定できないため、Provider 有効化時にこの許容リスクを `warning` で出力する。

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
- サーバー移動時の `DRAINING` / `WARMING_UP` / 再同期で旧サーバーの成功済み操作を吸収しきれなかった。
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

### 5.8 サーバー移動と READY 遅延

同一プレイヤーに対して、同期 Vault 取引を受け付ける Provider キャッシュは「自サーバーでオンラインかつ `READY`」の場合だけ有効にする。
Man10BankService 側に短命な所有権トークンは持たせない。
代わりに、サーバー移動直後の二重成功リスクを `DRAINING`、join 後クールタイム、再同期で緩和する。

- join 時は Provider キャッシュを `LOADING` にする。
- 初回ロードに成功してもすぐ `READY` にせず、`WARMING_UP` にする。
- `WARMING_UP` 中は `getBalance` などの読み取り表示は許可してよいが、`has` は `false`、`depositPlayer` / `withdrawPlayer` は `FAILURE` にする。
- `vault.joinReadyDelayMillis` のクールタイム後、Man10BankService から権威残高を再取得し、送信待ちキューが健全なら `READY` にする。
- quit / kick / transfer 検知時は対象 UUID の Provider キャッシュを `DRAINING` にし、新規 Provider 書き込みを止める。
- `DRAINING` 中は対象 UUID の送信待ちキューを可能な限り flush し、`vault.quitDrainTimeoutMillis` を超えた場合は残りを通常の送信待ちキューまたは永続キューで後送する。
- `DRAINING` 完了後、対象 UUID の Provider キャッシュを破棄する。

この方式は短命な所有権トークンによる厳密な fencing ではない。
旧サーバーの成功済み未送信操作がクールタイム内に DB へ反映されることを期待する折衷案であり、
反映が間に合わなかった場合は Man10BankService の DB 制約で不足を拒否し、VaultService が `CONFLICT` として扱う。
唯一の真実は引き続き `user_vault`。

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
    val pendingOperations: Map<String, PendingVaultOperation>,
    val status: Status,
    val readyAfterMillis: Long?,
    val lastSyncedAtMillis: Long,
)

data class PendingVaultOperation(
    val operationId: String,
    val amount: Long,
    val source: PendingSource, // PROVIDER / MAN10_API
    val createdAtMillis: Long,
)
```

概念:


| 値                  | 意味                                                                                                                                           |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `confirmedBalance` | Man10BankService で確認済みの残高。                                                                                                                   |
| `confirmedVersion` | `user_vault.Version`。古い push や再同期結果を捨てるために使う。                                                                                                |
| `pendingOperations` | Provider 経路または内製 API 経路で予約済みだが、まだ Man10BankService で確定していない減算操作。`operationId` ごとに保持する。未確定の入金は含めない。                                               |
| `pendingDelta`     | `pendingOperations.values.sumOf { -it.amount }` で計算する値。保存フィールドにはしない。常に 0 以下。未確定の入金は含めない。                                                      |
| `visibleBalance`   | `confirmedBalance + pendingDelta`。`getBalance` が返す値。DB 未確定の入金は表示へ加えない。                                                                       |
| `availableBalance` | `confirmedBalance + pendingDelta`。外部 Provider 経路と内製 API 経路の `withdraw` / `/pay` / `user_vault -> user_bank` 可否判定に使う計算値。DB 未確定の入金は利用可能額へ加えない。 |
| `status`           | `LOADING`（読み込み中）/ `WARMING_UP`（join 直後のクールタイム中）/ `READY`（取引可能）/ `STALE`（古い可能性あり）/ `DRAINING`（キュー処理中）/ `CONFLICT`（競合停止中）/ `DISABLED`（停止中）。    |
| `readyAfterMillis` | `WARMING_UP` を解除して再取得を試みる時刻。`READY` などでは `null`。                                                                                             |


残高は内部では `Long`（円）で保持し、Vault 境界でだけ `Double` に変換する。
未確定の減算予約は必ず `operationId` ごとの `pendingOperations` として保持し、
`pendingDelta` はその合計から都度計算する。これにより複数の未確定操作の一部だけが成功・失敗した場合でも、
該当する `operationId` だけを消し込める。
Provider 書き込みを許可するかどうかは、各エントリの `status` に加えて VaultService 全体の書き込み健全性
（`WRITE_READY` / `DEGRADED` / `DOWN` / `DRAINING`）も見る。
残高上限は Man10BankService の config API または push で受け取った権威設定値を VaultService 全体で保持し、
Provider の同期判定に使う。上限値が未取得の間は書き込みを `FAILURE` にする。

### 6.2 同期 API の挙動

以下の処理はすべてメインスレッド上で実行する。外部プラグインが off-main から呼んだ場合、
Man10BankProvider は処理を Bukkit メインスレッドへ同期ディスパッチし、呼び出し元スレッドは結果が返るまで待つ。
HTTP や DB の完了を待つのではなく、Provider キャッシュと送信待ちキューの同期処理だけを待つ。


| Vault(Economy) メソッド              | Provider の挙動                                                                                                                                              |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `getBalance(player)`             | `READY` または `WARMING_UP` なら `visibleBalance` を返す。未ロード時は `0` を返し、非同期ロードを要求する。                                                                              |
| `has(player, amount)`            | 金額を整数円へ切り捨てて正規化し、`READY` かつ書き込み健全性が `WRITE_READY` かつ `availableBalance >= normalizedAmount`。正規化失敗、未ロード、古い可能性がある状態、サービス不健康時は `false`。                      |
| `withdrawPlayer(player, amount)` | `READY`、書き込み健全性 `WRITE_READY`、金額正規化成功、残高十分、送信待ちキューへの登録成功なら `operationId` ごとの減算予約を `pendingOperations` へ追加して `SUCCESS`。それ以外は `FAILURE`。                    |
| `depositPlayer(player, amount)`  | `READY`、書き込み健全性 `WRITE_READY`、金額正規化成功、送信待ちキューへの登録成功なら正規化後の整数額で `SUCCESS`。DB 確定までは Provider キャッシュ残高を増やさず、確定応答後に `confirmedBalance` を更新する。それ以外は `FAILURE`。  |
| `hasAccount(player)`             | 対象が同一サーバーでオンラインかつ Provider キャッシュが `LOADING` / `WARMING_UP` / `READY` なら `true`。オフライン、解決不能、キャッシュなしは `false`。                                               |
| `createPlayerAccount(player)`    | 同一サーバーのオンライン対象について VaultService の ensure / load 要求を受理できれば `true`。オフライン、解決不能、要求登録失敗は `false`。`true` は DB コミット済みを意味せず、`READY` になるまで金銭操作は失敗する。               |
| `isEnabled()`                    | Provider が ServicesManager に登録済みかつ `vault.providerEnabled = true` なら `true`。Service の `DEGRADED` / `DOWN` / `DRAINING` では `true` のままにし、取引メソッド側で書き込みを拒否する。 |
| `format(amount)`                 | 有限値の小数部を 0 方向へ切り捨て、3 桁カンマと `円` を付ける。例: `1234.9 -> "1,234円"`、`-1234.9 -> "-1,234円"`。NaN / Infinity は `"0円"`。                                               |
| bank 系 API                       | `hasBankSupport() = false`、bank 系は `NOT_IMPLEMENTED`。                                                                                                     |


### 6.3 overload / メタデータ

- 電子マネーは全 world 共通の単一通貨とする。world 引数付き overload は world 名を無視し、対応する通常版へ委譲する。
- `String` プレイヤー指定は Minecraft ID として扱い、同一サーバーで現在オンラインのプレイヤーだけを解決する。任意の名前からオフライン UUID を生成しない。
- `OfflinePlayer` 指定も、対象が同一サーバーで現在オンラインの場合だけ UUID を使って処理する。オフライン時は未ロード時の規則に従う。
- `currencyNameSingular()` と `currencyNamePlural()` はどちらも `"円"` を返す。
- `getName()` は `"Man10Bank"`、`fractionalDigits()` は `0`、`hasBankSupport()` は `false`。
- bank 系メソッドは `EconomyResponse.ResponseType.NOT_IMPLEMENTED`、`amount = 0`、`balance = 0` を返し、`getBanks()` は空リストを返す。

### 6.4 送信待ちキューの操作

Provider 書き込み成功時に作る操作:

```kotlin
data class VaultQueuedOperation(
    val operationId: String,
    val serverName: String,
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
- `serverName` はプラグイン側 config の自己申告値を監査・障害調査用に記録する。Service 側の fencing token としては使わない。
- Provider は送信待ちキューへの登録に失敗した操作を `SUCCESS` にしてはならない。
- 通常時はメモリ上の送信待ちキューへ登録できれば `SUCCESS` を返す。
- 永続キューの対象は、Provider が外部プラグインへ `SUCCESS` を返した後、まだ Man10BankService へ確定送信できていない外部 Vault 取引のみ。
- Man10BankService の不調を検知したら、未送信のメモリキューをプラグインデータフォルダ配下の SQLite または追記型ログファイルへ退避する。
- 復旧後、永続キューを同じ `operationId` で処理し、成功した操作は削除または `COMPLETED` 化する。
- 送信待ちキューが詰まった場合、Provider キャッシュを `STALE` または `DISABLED` にして新規書き込みを止める。
- Paper クラッシュ時にメモリキューから永続キューへ退避できていない操作は失われ得る。これは本折衷案の既知リスクとして扱う。

### 6.5 収束

VaultService は次のイベントで Provider キャッシュを更新する。


| イベント                      | 更新内容                                                                                                                      |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 初回ロード成功                   | `confirmedBalance` / `confirmedVersion` をセットし、`WARMING_UP` にする。                                                           |
| join クールタイム経過後の再取得成功      | 権威残高・version を再取得し、送信待ちキューが健全なら `READY` にする。                                                                              |
| キュー操作の確定成功                | 対応する `operationId` の Provider 未確定操作を `pendingOperations` から外し、Man10BankService の返した確定残高・version を反映する。入金による増額もこの時点で初めて反映する。 |
| キュー操作の業務失敗                | 対応する `operationId` の予約を取り消し、`CONFLICT` として扱う。必要なら Man10BankService から権威残高を再取得する。                                             |
| 内製 API 操作の確定成功            | 対応する `operationId` の内製 API 予約を `pendingOperations` から外し、Man10BankService の返した確定残高・version を反映する。                                  |
| 内製 API 操作の失敗              | 対応する `operationId` の予約を取り消し、必要なら Man10BankService から権威残高を再取得する。                                                           |
| Man10BankService からの push | `version` が新しければ `confirmedBalance` / `confirmedVersion` を更新し、未確定の減算予約だけを再適用して `visibleBalance` を計算する。                    |
| 定期再同期                     | 権威残高を再取得し、同様に未確定の減算予約だけを再適用する。                                                                                            |
| 競合検知                      | `CONFLICT` にして新規 Provider 書き込みを止め、手動確認できるログを残す。                                                                           |


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
4. 自サーバーにいないプレイヤーへの減算 API を拒否し、単独の `deposit` と管理者 `setBalance` / `editvault` だけ Man10BankService へ送る。
5. join / quit / kick / transfer 時の `WARMING_UP` / `DRAINING` 管理。
6. Man10BankService からの push 受信、または定期再同期。
7. health check / WebSocket heartbeat / 直近送信結果による書き込み健全性の管理。
8. Provider キャッシュの `WARMING_UP` / `READY` / `STALE` / `CONFLICT` 管理。
9. Provider 書き込みを受け付けてよいかの健全性判定。

### 7.2 非同期 API

Man10BankAPI / コマンド / 内製プラグインは以下のような非同期メソッドだけを使う。


| メソッド                                    | 用途                                                                                                                                         |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `getBalance(uuid)`                      | Man10BankService から権威残高を取得。オンラインなら Provider キャッシュも更新。                                                                                      |
| `deposit(uuid, amount, reason)`         | 電子マネーを権威入金。通常の内製 API では、対象が自サーバーにいなくても直接 Man10BankService へ送れる唯一の電子マネー操作。                                                                  |
| `withdraw(uuid, amount, reason)`        | 電子マネーを権威出金。対象が自サーバー在席かつ Provider キャッシュ `READY` なら先にローカル予約し、自サーバーにいない場合または `WARMING_UP` 中は拒否。                                               |
| `transfer(from, to, amount, reason)`    | `/pay`。送金元と送金先が同一の自サーバー上でオンライン、かつ双方の Provider キャッシュが `READY` の場合だけ実行する。電子マネー -> 電子マネー以外には使わない。                                             |
| `moveVaultToBank(uuid, amount, reason)` | `/deposit`。対象が自サーバー在席かつ Provider キャッシュ `READY` なら vault 側を先にローカル予約し、`user_vault -> user_bank` を 1 Tx で移動。自サーバーにいない場合または `WARMING_UP` 中は拒否。 |
| `moveBankToVault(uuid, amount, reason)` | `/withdraw`。対象が自サーバー在席かつ Provider キャッシュ `READY` の場合だけ、`user_bank -> user_vault` を 1 Tx で移動。自サーバーにいない場合または `WARMING_UP` 中は拒否。               |
| `setBalance(uuid, amount, reason)`      | 管理者操作。対象の在席状況を問わず Man10BankService へ権威設定を送る。減額や古い Provider キャッシュとの衝突で Service 側が拒否した場合は、コマンド実行時の失敗レスポンスとして返し、以後は運用対応する。 |


これらは Man10BankService の確定応答を待つ。
成功後、VaultService は Provider キャッシュが存在する UUID について確定残高を反映する。

残高を減らす操作（`withdraw`、`transfer` の送金元、`moveVaultToBank`）は、Man10BankService へ送る前に
対象が自サーバーに在席し、Provider キャッシュが `READY` の場合だけローカル Vault 台帳で予約する。
予約できない場合は、その時点で不足として失敗させる。
対象が自サーバーにいない場合、または `WARMING_UP` 中の場合、減算操作は拒否する。
通常の内製 API では、単独 UUID の残高を増やす操作（`deposit`）だけは、自サーバーにいない対象でも Man10BankService へ直接送ってよい。
`transfer` はこの規則の対象外であり、`/pay` 用として送金元と送金先が同一 Paper 上にいる場合だけ許可する。
対象が同一サーバーでオンラインの場合も、正の未確定差分はローカル Vault 台帳へ反映せず、Man10BankService の DB 更新完了後に確定残高を反映する。
対象が自サーバーにいない場合は、単独の `deposit` と管理者 `setBalance` / `editvault` だけ許可する。
`transfer` の受取人、`moveBankToVault` は、結果として `user_vault` が増える場合でもこの例外には含めない。
オフラインプレイヤーのその他の資産操作には既存 Bank 機能を使う。

### 7.3 Provider 送信待ちキューの処理

処理の流れ:

1. Provider が送信待ちキューに操作を追加する。
2. VaultService が登録順に操作を取得する。
3. Man10BankService へ `operationId` / `serverName` / `uuid` / `amount` を送る。
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


| メソッド                 | 概要                                                                    | 返却                                                             |
| -------------------- | --------------------------------------------------------------------- | -------------------------------------------------------------- |
| `GET {uuid}/balance` | 電子マネー残高取得                                                             | `{ balance, version }`                                         |
| `GET {uuid}/logs`    | 電子マネー取引ログ                                                             | `VaultLog[]`                                                   |
| `GET config`         | Vault 設定取得。残高上限と Provider 移動緩和設定を配布する。                                | `{ maxBalance, joinReadyDelayMillis, quitDrainTimeoutMillis }` |
| `POST deposit`       | 権威入金。内製 API と Provider 送信待ちキューの両方で使う。                                 | `{ balance, version }`                                         |
| `POST withdraw`      | 権威出金。不足時は `409`。                                                      | `{ balance, version }`                                         |
| `POST transfer`      | 電子マネー -> 電子マネー。送金元・送金先が同一 Paper 上でオンラインの `/pay` 用。                    | from/to の残高・version                                            |
| `POST move`          | `user_vault` と `user_bank` の相互移動。`/deposit` `/withdraw` 用。ATM には使わない。Bank 更新は既存 `BankService` 経路に統合する。 | vault/bank の残高                                                 |
| `POST set`           | 管理者用の絶対値設定。在席状況を問わず受理し、衝突や制約違反はレスポンスで返す。                              | `{ balance, version }`                                         |
| `GET ws`             | push / config 更新通知。                                                   | WebSocket                                                      |


### 8.2 書き込み共通要件

- すべての書き込みは DB トランザクション内で行う。
- `user_vault` の対象行をロックし、残高不足や負数をサービス側で拒否する。
- 成功時に `version++`。
- `vault_log` に記録する。
- `operationId` が指定された場合は UNIQUE とし、同じ `operationId` の再送には同じ結果を返す。
- `serverName` はプラグイン側 config の自己申告値を監査用に記録する。Man10BankService は短命な所有権トークンによる fencing は行わない。
- 正規化後の操作金額が設定済みの `Vault:MaxBalance` を超える場合は拒否する。残高を増やす操作と絶対値設定は更新後残高も検証し、上限を超える場合は拒否する。
- コミット後に push を発行する。
- オンライン状態や同一 Paper 上にいることの検証は、Man10BankService ではなく呼び出し元 Paper の VaultService が行う。
- Man10BankAPI / VaultService 由来の内製 Vault 書き込みで、対象が自サーバーにいない場合は単独の `deposit` と管理者 `set` だけ送信してよい。
- 自サーバーにいないプレイヤーのその他の資産操作は既存 Bank API の責務であり、VaultController では扱わない。

### 8.3 `move` の Bank 更新経路

`POST move` は `user_vault` と `user_bank` を 1 DB トランザクションで更新するが、
`user_bank` の更新は既存 Bank 経路から外さない。

- `VaultController` / `VaultService` は既存の `BankService.RunExclusiveAsync` 上で `move` を実行する。
- `user_vault` 行をロックして vault 側の増減を確定し、同じ `BankDbContext` / transaction 内で `BankRepository.ChangeBalanceCoreAsync` を呼んで `user_bank` と `money_log` を更新する。
- `BankService.DepositAsync` / `WithdrawAsync` と同じ入口直列化キューに載せるため、既存 Bank 操作、小切手、ローンなどの `user_bank` 更新と競合しない。
- ロック順序は `user_vault` 行 -> `user_bank` 行に統一する。`user_bank` 行のロックと作成、`money_log` 追加は `BankRepository.ChangeBalanceCoreAsync` に寄せる。
- `move` で bank 側が残高不足、検証失敗、または DB 制約違反になった場合はトランザクション全体をロールバックし、呼び出し元へ失敗を返す。

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

### 9.2 `user_bank` の一意性

Vault の `move` は `user_vault` と `user_bank` を同一 UUID の 1 口座として扱うため、
`user_bank.uuid` も UNIQUE にする。

```sql
alter table user_bank
    add unique key uq_user_bank_uuid (uuid);
```

導入前に既存の重複 `uuid` を整理する。
直列化は引き続き既存の `BankService.RunExclusiveAsync` で行うが、これは同一プロセス内の更新順序を揃えるためのもの。
UUID UNIQUE は重複行を作らせず、`SELECT ... WHERE uuid = ... FOR UPDATE` の対象を 1 行に固定するために必要。

### 9.3 `vault_log`

電子マネー専用ログ。銀行の `money_log` とは混ぜない。

追加で持つべき項目:


| 項目              | 用途                                                                |
| --------------- | ----------------------------------------------------------------- |
| `operation_id`  | Provider 送信待ちキュー / Man10BankAPI の冪等キー。nullable でもよいが、指定時は UNIQUE。 |
| `source`        | `PROVIDER` / `MAN10_API` / `ADMIN` / `SYSTEM`。                    |
| `server`        | 操作元 Paper サーバー名。                                                  |
| `balance_after` | 操作後残高。監査と重複応答用。                                                   |


### 9.4 Provider 移動緩和設定

Man10BankService は在席管理用の永続テーブルを持たない。
サーバー移動直後の競合は Paper 側の状態遷移と設定値で緩和する。

設定項目:


| 項目                             | 用途                                                                |
| ------------------------------ | ----------------------------------------------------------------- |
| `vault.joinReadyDelayMillis`   | join 後、Provider キャッシュを `WARMING_UP` に置く時間。既定値は 3000 ms。           |
| `vault.quitDrainTimeoutMillis` | quit / transfer 時に対象 UUID の送信待ちキュー flush を待つ最大時間。既定値は 3000 ms。    |
| `Vault:MaxBalance`             | Man10BankService 側の残高上限。`GET config` と config push で Paper へ配布する。 |


---

## 10. プラグイン側コンポーネント


| コンポーネント                               | 役割                                                  |
| ------------------------------------- | --------------------------------------------------- |
| `economy/Man10BankProvider.kt`        | `Economy` 実装。外部 Vault Consumer から呼ばれる同期互換レイヤ。       |
| `service/vault/VaultService.kt`       | プラグイン側の非同期 vault サービス。Man10BankService への唯一の書き込み窓口。 |
| `service/vault/VaultProviderCache.kt` | Provider が読む同期キャッシュ。VaultService が収束更新する。           |
| `service/vault/VaultWriteQueue.kt`    | Provider 成功操作の送信待ちキュー。冪等キーを管理する。                    |
| `service/vault/VaultSyncClient.kt`    | WebSocket push / config 更新 / 再同期。                   |
| `api/VaultApiClient.kt`               | `/api/Vault/*` REST クライアント。                         |
| `Man10BankAPI.kt`                     | 内製プラグイン向けの非同期公開 API。                                |
| `listener/VaultLifecycleListener.kt`  | join/quit で load、`WARMING_UP`、キュー処理、cache 破棄を行う。    |


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


| 処理                                  | スレッド                                                                 |
| ----------------------------------- | -------------------------------------------------------------------- |
| Man10BankProvider の全 `Economy` メソッド | メインスレッド同期。off-main から呼ばれた場合はメインスレッドへ同期ディスパッチして結果を返す。HTTP 待ちはしない。     |
| Provider キャッシュ操作                    | メインスレッドへ直列化する。VaultService の確定応答、push、再同期による更新もメインスレッドへディスパッチして適用する。 |
| 送信待ちキューの処理                          | `Dispatchers.IO`。                                                    |
| Man10BankService REST               | `Dispatchers.IO`。                                                    |
| WebSocket push / 再同期                | `Dispatchers.IO`。                                                    |
| コマンド結果のプレイヤー通知                      | 必要に応じてメインスレッドへ戻す。                                                    |


---

## 11. 既存処理への影響

### 11.1 `VaultManager` の置換

既存の `[VaultManager](../src/main/java/red/man10/man10bank/service/VaultManager.kt)` は外部 Economy Provider を取得する Consumer。
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

対象プレイヤーが自サーバーにいない場合、`/deposit` `/withdraw` はどちらも不可にする。
自サーバーにいないプレイヤーの資産操作は既存 Bank 機能を使う。

クライアント側の補償ロジックは削除する。

### 11.3 `/pay` / `/mpay`

電子マネー送金 `/pay` は `Man10BankAPI -> VaultService -> Man10BankService transfer`。
Provider は経由しない。
ただし送金元の電子マネーは、Man10BankService へ送る前に在席サーバーのローカル Vault 台帳で予約する。
これにより同時に外部ショップ購入が来ても、送金元残高を二重に使わない。

`/pay` は送金元と送金先が同一 Paper 上でオンラインの場合だけ成功させる。
送金先を UUID だけで解決して自サーバー外へ送る実装は禁止する。
送金する資産は送金元・送金先ともに電子マネー (`user_vault`) であり、Bank 残高との相互移動には使わない。
受取人の Provider キャッシュは Man10BankService の DB 更新完了後に増やす。

`/mpay` は既存の Bank -> Bank 送金コマンドとして維持し、VaultService / `user_vault` を経由させない。
コマンド名、対象資産、経路はこの区分で固定する。

### 11.4 ATM

ATM の現金 <-> 電子マネー変換は Vault API を呼ばず、VaultService の非同期 API を使う。
同一プレイヤーの ATM 操作は 1 件ずつ直列化し、確定待ちの間は対象 UI の再実行と対象アイテムの移動を禁止する。
Bukkit インベントリ操作はメインスレッド、VaultService と Man10BankService の呼び出しは非同期で行い、
結果を待ってからメインスレッドへ戻して次のアイテム操作を行う。

現金 -> 電子マネーは次の順序に固定する。

1. メインスレッドで対象現金アイテム、合計額、所有数を再検証する。
2. 現金アイテムを先に消費し、消費できたことを確定する。
3. VaultService の権威入金を呼び、Man10BankService の DB コミット結果を待つ。
4. DB 成功後に Provider キャッシュを増やし、ATM 操作を成功完了する。
5. DB が未コミットと確定できた失敗だけアイテム返却を試みる。タイムアウトなどコミット有無が不明な場合は自動返却せず、再照会対象として `severe` ログを残す。

電子マネー -> 現金は次の順序に固定する。

1. メインスレッドで現金アイテムを生成可能か、インベントリへ収容可能かを事前検証する。この時点では付与しない。
2. VaultService で電子マネーを予約して権威出金し、Man10BankService の DB コミット結果を待つ。
3. DB 成功後に Provider キャッシュへ確定残高を反映し、メインスレッドで現金アイテムを 1 回だけ付与する。
4. 付与できなかったことを確定できる全量または残量だけ、冪等キー付きの権威入金で返金を試みる。付与結果が不明な場合は再付与も自動返金も行わず、`severe` ログを残す。

クラッシュや結果不明時に消失と増殖のどちらかしか避けられない場合は、消失側へ倒す。
現金付与後の出金、DB 結果不明時のアイテム返却、付与結果不明時の再付与・自動返金は禁止する。

### 11.5 残高表示

`BalanceRegistry` の `id = "vault"` は維持する。
表示値は原則 Provider キャッシュ由来とし、キャッシュがない場合は VaultService に非同期ロードを依頼する。

---

## 12. 金額・型の扱い

- DB / サービス内部 / Provider キャッシュは整数円を `Long` または `decimal(20)` で扱う。
- Vault(Economy) の境界のみ `Double`。
- `fractionalDigits() = 0`。
- Vault API と内製 API の入力金額は、有限かつ正数であることを確認してから小数部を切り捨て、整数円へ正規化する。
- `0 < amount < 1` のように切り捨て後が 0 円になる金額、0 以下、NaN、Infinity は拒否する。
- 応答、`vault_log`、`operationId` に紐づく冪等結果には、要求時の小数値ではなく正規化後の整数額を使用する。
- 残高上限は Man10BankService の `Vault:MaxBalance` で設定可能とし、既定値を `1_000_000_000_000` 円（1 兆円）にする。
- Man10BankService を上限値の唯一の権威とし、`GET /api/Vault/config` または config push で Paper の VaultService / Provider へ配布する。Provider は上限未取得中の書き込みを拒否する。
- 正規化後の操作金額が上限を超える場合は、Provider と Man10BankService の双方で拒否する。
- deposit、transfer の受取人、Bank -> Vault の move、増額 set の更新後残高が上限を超える場合は拒否する。設定変更時点ですでに上限を超えている残高でも、残高を減らす操作は許可する。
- 設定値は 1 以上かつ IEEE 754 `Double` で整数を正確に表現できる `9_007_199_254_740_991` 以下に限定する。不正な設定では Man10BankService の Vault API を fail-closed で無効化し、`severe` ログを出す。
- `double` 同士の累積演算で残高を保持しない。

---

## 13. 障害・エッジケース


| ケース                           | 方針                                                                                                                                                                                                                           |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Provider キャッシュ未ロード            | 書き込みは `FAILURE`。読みは `0` / `false` を返し、非同期ロードを要求する。                                                                                                                                                                           |
| Vault API の off-main 呼び出し     | Bukkit メインスレッドへ同期ディスパッチし、Provider キャッシュと送信待ちキューの処理結果を呼び出し元へ返す。HTTP / DB は待たない。                                                                                                                                               |
| 小数金額                          | 小数部を切り捨てて整数円として扱う。切り捨て後が 0 円なら拒否する。                                                                                                                                                                                          |
| 残高上限                          | Man10BankService の `Vault:MaxBalance` を権威とし、既定 1 兆円。操作金額、増額後残高、絶対値設定が超える場合は拒否する。既存の上限超過残高を減らす操作は許可する。                                                                                                                        |
| 外部ショップ購入と内製 `/pay` が同時に残高を減らす | 両方が同じローカル Vault 台帳へ先に予約する。先に予約した方が `availableBalance` を減らすため、合計額が残高を超える場合は後続がローカル不足で失敗する。                                                                                                                                    |
| join 直後                       | Provider キャッシュは `WARMING_UP`。読み取り表示は許可してよいが、`has` は `false`、入出金は `FAILURE`。クールタイム後に権威残高を再取得して `READY` にする。                                                                                                                   |
| quit / transfer 直後            | Provider キャッシュを `DRAINING` にし、新規 Provider 書き込みを止める。対象 UUID の送信待ちキューを可能な限り flush し、タイムアウト後は通常キューまたは永続キューで後送する。                                                                                                                |
| 自サーバーにいないプレイヤーの残高を減らす         | 拒否する。別 Paper の Provider キャッシュを事前に減らせず、stale-high による過払いが起き得るため。                                                                                                                                                              |
| 自サーバーにいないプレイヤーの残高を増やす         | 単独の `deposit(uuid, amount, reason)` だけ直接 Man10BankService で権威更新してよい。Provider キャッシュには push/再同期で反映する。push 前の一時的な低い残高による失敗は許容する。                                                                                                |
| DB 未確定の入金                     | Provider キャッシュ、`visibleBalance`、`availableBalance` に加えない。Man10BankService の DB 更新完了後にだけ反映する。                                                                                                                                 |
| 自サーバーにいないプレイヤーへの内製 Vault 操作   | 単独の `deposit(uuid, amount, reason)` と管理者 `setBalance` / `editvault` だけ許可する。`/deposit` `/withdraw` `/pay`、`withdraw`、`transfer`、`move` は拒否する。その他の資産操作は既存 Bank 機能を使う。                                                        |
| Man10BankService 到達不能         | `isEnabled()` は `true` のまま、書き込み健全性を `DOWN` / `DEGRADED` にする。Provider の `depositPlayer` / `withdrawPlayer` / `has` は新規 `FAILURE` / `false`、`getBalance` はキャッシュ値。成功返却済みで未送信の Provider 操作は永続キューへ退避し、復旧後に同じ `operationId` で再送する。 |
| 送信待ちキューの未処理件数過多               | Provider 書き込みを止める。外部ショップには `FAILURE` を返す。                                                                                                                                                                                    |
| Provider 成功後の service 失敗      | `CONFLICT`。自動補償せず、権威残高で収束し、重大ログを残す。                                                                                                                                                                                          |
| HTTP タイムアウト                   | Provider キュー操作は同一 `operationId` で再送。内製 API は結果不明として呼び出し元へ失敗または再確認を返す。                                                                                                                                                        |
| WebSocket 切断                  | 定期再同期に落とす。再接続後にオンライン全員を全件再同期。                                                                                                                                                                                                |
| サーバークラッシュ                     | `user_vault` が真実。永続キューへ退避済みの操作は再起動後に同じ `operationId` で再送する。メモリキューにしか無かった Provider 成功返却済み操作は失われ得るため、既知リスクとして運用ログ・監視対象にする。                                                                                                     |
| ATM の現金 -> 電子マネー              | 現金アイテムを確定消費してから DB 入金する。DB 結果不明時はアイテムを自動返却しない。                                                                                                                                                                               |
| ATM の電子マネー -> 現金              | DB 出金を確定してから現金アイテムを 1 回だけ付与する。付与結果不明時は再付与・自動返金をしない。                                                                                                                                                                          |
| 二重 Provider 登録                | 実効 Provider が自分でなければ Provider 機能を停止し、severe ログ。                                                                                                                                                                              |
| サーバー移動直後の旧キュー競合               | `joinReadyDelayMillis` と再取得で緩和する。旧サーバーの成功済み操作が間に合わず DB 側で不足になった場合は `CONFLICT` として扱う。                                                                                                                                         |
| 管理者 set/edit                  | 対象の在席状況を問わず Man10BankService で権威更新する。衝突、残高制約違反、DB エラーはコマンド実行時の失敗レスポンスとして返し、以後は運用で対応する。Provider キャッシュは push/再同期で収束。                                                                               |


---

## 14. セキュリティ

- REST 書き込みは既存の `RequireWriteScope` を使う。
- WebSocket も Bearer 認証を必須にする。
- Paper -> Man10BankService の API キーは `config.yml` / 環境変数管理とし、コミットしない。
- `serverName` はプラグイン側 config の自己申告値でよい。認可判断には使わず、監査・障害調査用の操作元ラベルとして扱う。
- `operationId`、`serverName`、`source` は監査ログに残す。

---

## 15. 既知のリスク

### 15.1 許容する既知のリスク

- 外部 Vault API 経路は `SUCCESS` を返した時点では DB コミット済みではない。外部ショップが商品を渡した後に Man10BankService 側で失敗した場合、自動補償は汎用的にできない。このリスクは許容し、検知時は `operationId`、uuid、amount、呼び出し元、失敗理由を `severe` ログへ出力する。
- Provider が `SUCCESS` を返した後、未送信操作を永続キューへ退避する前に Paper プロセスがクラッシュすると、その取引は失われ得る。このリスクは許容する。退避失敗や正常 shutdown 時の未退避操作など検知可能な事象は `severe` ログへ出力し、Provider 有効化時にもこの動作モードを `warning` で通知する。強制終了で失われた操作そのものは事後に特定できない場合がある。
- サーバー移動・kick・transfer・クラッシュ時に旧サーバーの成功済みキュー操作が新サーバーの `READY` 前に反映されないと、外部プラグインには成功済みだが DB では失敗する状態になり得る。このリスクは許容し、検知時は `CONFLICT` として Provider 書き込みを止め、重大ログと運用対応の対象にする。

### 15.2 確定した設計判断

- DB 未確定の正の差分は `availableBalance` と `visibleBalance` に含めず、Provider キャッシュも増やさない。Man10BankService の DB 更新完了後に確定残高として反映する。
- 既存電子マネーの移行は別タスクとし、現時点の Provider 設計では移行完了フラグや起動時 fail-closed を扱わない。
- `/pay` は同一 Paper 上でオンラインのプレイヤー間の電子マネー -> 電子マネー送金、`/mpay` は Bank -> Bank 送金に固定する。`/pay` による電子マネーと Bank 間の移動は実装しない。
- ATM は 11.4 の順序で Man10BankService の確定結果を待つ。結果不明時に消失と増殖の一方しか避けられない場合は消失を許容し、再付与・自動返金による増殖を防ぐ。
- 小数金額は整数円へ切り捨てて統一する。切り捨て後が 0 円になる入力は拒否する。
- Vault 残高上限は Man10BankService の `Vault:MaxBalance` で設定可能とし、既定値は 1 兆円にする。Service の設定値を権威として config API / config push で Provider へ配布する。
- Man10BankService 側に在席管理は持たせない。join 直後は `WARMING_UP` とし、クールタイム後の再取得で `READY` にする。
- `joinReadyDelayMillis` と `quitDrainTimeoutMillis` は config で設定可能にし、既定値は 3000 ms とする。
- `serverName` はプラグイン側 config の自己申告値でよい。認可や整合性判定には使わず、監査・障害調査用の操作元ラベルとして扱う。
- 管理者 `setBalance` / `editvault` は対象の在席状況を問わず許可する。衝突や残高制約違反はコマンド実行時の失敗レスポンスとして返し、以後は運用で対応する。
- 未確定の減算予約は `operationId` ごとの `pendingOperations` として保持し、`pendingDelta` はその合計から計算する。
- `POST move` の `user_bank` 更新は既存 `BankService.RunExclusiveAsync` 上で実行し、`BankRepository.ChangeBalanceCoreAsync` を使って `user_bank` と `money_log` を同一 transaction に載せる。
- `user_bank.uuid` は UNIQUE にする。既存重複データは導入前に整理する。
- idempotency 専用テーブルは作らず、`vault_log.operation_id` の UNIQUE を冪等キーとして使う。複数ログを伴う操作の応答復元が曖昧な場合は、運用ログと再照会で扱う。
- 外部プラグインから Vault API が off-main で呼ばれた場合は、Bukkit メインスレッドへ同期ディスパッチする。Provider キャッシュの全更新もメインスレッドへ直列化する。
- Vault Economy の world 引数付き overload は world 名を無視し、全 world で同じ電子マネー残高を扱う。
- String 指定は同一サーバーでオンラインの Minecraft ID だけを解決し、任意のオフライン UUID は生成しない。
- `hasAccount` はオンラインかつキャッシュが `LOADING` / `WARMING_UP` / `READY` なら `true`。`createPlayerAccount` はオンライン対象の ensure / load 要求を受理できれば `true` とし、金銭操作は `READY` まで拒否する。
- `currencyNameSingular()` / `currencyNamePlural()` はどちらも `"円"` とする。
- `format(double)` は小数部を切り捨てて 3 桁カンマと `円` を付け、`1234.9` は `"1,234円"` と表示する。
- `isEnabled()` は Provider が登録済みかつ設定上有効なら `true` とし、一時的な Service 障害では `false` にしない。障害中の `has` は `false`、入出金は `FAILURE` とし、復旧後は再登録なしで再開する。

### 15.3 保留中・未解決のリスク / 実装上の判断

本節は初回実装(`feature/vault-provider-impl`)で**保留した項目**と、設計本文に対して
実装時に確定させた**補足的な設計判断**を記録する。保留項目は機能としては設計済みだが、
追加リスクの封じ込めや段階導入のため実装を後送している。

#### A. 実装を保留した項目

- **ATM の現金 ⇔ 電子マネー変換(§11.4)** — 保留。
  - 現状: `AtmService` の `depositCashToVault` / `withdrawVaultToCash` は残高・アイテムを一切動かさず、
    「電子マネー基盤の刷新中のため一時停止」と案内するのみ。
  - 理由: 旧実装は `VaultManager` の**同期** deposit/withdraw に依存していた。Vault Provider 化で
    電子マネーは**非同期の権威更新**(DB 確定待ち)になったため、§11.4 の順序(現金消費の確定 → 権威入金 →
    結果不明時は自動返却しない 等)を満たす非同期フローへ作り替える必要がある。これを誤って同期流用すると
    増殖/消失を招くため、慎重な設計・テストを要する。`/deposit` `/withdraw`(move)・`/pay`(transfer)・
    外部ショップ(Provider)は本リリースで稼働する。

- **プラグイン側 WebSocket push クライアント `VaultSyncClient`(§7.4)** — 保留。
  - 現状: Man10BankService 側の push ハブ(`/api/Vault/ws`・`VaultWsHub`)は実装済み。プラグイン側の
    WebSocket 受信クライアントは未実装。
  - 収束は当面、**定期再同期(`vault.resyncIntervalMillis`、§7.4 のフォールバック)+ 確定応答での反映 +
    `operation_id` 冪等**で担保する。push 導入は収束レイテンシの改善であり、整合性の前提ではない。

- **Provider 送信待ちキューの永続退避(§5.6 / §6.4)** — 保留。
  - 現状: 送信待ちキューはメモリ保持(`VaultWriteQueue`)。Man10BankService 不調検知時の SQLite / 追記ログへの
    退避と、復旧後の同一 `operation_id` 再送は未実装。
  - 影響: Paper クラッシュ時に「SUCCESS 返却済みかつ未送信」の Provider 操作が失われ得る。これは §15.1 の
    許容済み既知リスクと同種として扱い、Provider 有効化時に `warning` で通知する。永続化は本リスクの
    軽減策であり、完全な保証ではない(§5.6 と同じ)。

#### B. 実装時に確定させた補足的な設計判断

- **`vault_log` の冪等再送応答** — `version` 列は追加しない。`operation_id` が既存(再送)の場合は
  再適用せず、**現在の権威残高を再照会して返す**。`transfer` / `move` など複数ログを伴う操作も同様に
  現在残高の再照会で応答する。これは §15.2「idempotency 専用テーブルは作らず、複数ログ操作の応答復元が
  曖昧な場合は再照会で扱う」に沿う。`vault_log.operation_id` の UNIQUE が二重適用を防ぐ。

- **Provider キャッシュの並行性** — 「全更新をメインスレッドへ直列化」(§10.2)を、**不変エントリ +
  `ConcurrentHashMap` の原子的 per-key 更新**で実装した。IO スレッドからの収束更新と Provider
  (メインスレッド)の読み書きが、ロック/デッドロックなしに安全に共存する。Bukkit API を要する箇所
  (オンライン判定など)のみメインスレッドで実行する。効果は設計の意図(同一 UUID の予約・確定・取消の
  整合)と等価。

- **自サーバー在席判定** — 内製 API の減算経路では、Provider キャッシュの `status = READY` を
  「自サーバーでオンラインかつ取引可能」のシグナルとして用いる(ライフサイクルリスナがメインスレッドで
  `LOADING → WARMING_UP → READY`、`DRAINING → 破棄` を管理)。これにより IO スレッドからの Bukkit
  オンライン参照を避ける。

- **金額の型** — プラグイン内部・REST 送信ともに整数円(`Long`)。Man10BankService 側は `decimal(20,0)` で
  受け、整数性・上限を検証する。`/pay` 等の入力は切り捨てて整数化する。

- **`isProviderEnabled()` と内製経路の独立** — `isEnabled()`/Provider 登録状態は外部 Vault 経路のみを
  ゲートする。`/pay` `/deposit` `/withdraw` 等の内製コマンドは Vault 本体が無い環境でも VaultService 経由で
  動作する(書き込み健全性と `READY` 判定には従う)。
