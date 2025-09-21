# ServerLoan 実装サマリ（/mrevo）

## 追加・変更概要
- 新規サービス: `ServerLoanService`
  - 位置: `src/main/java/red/man10/man10bank/service/ServerLoanService.kt`
  - Listenerは継承しない設計。
  - 実装済み（いずれもプレイヤー単位）:
    - `suspend fun get(player: Player): Result<ServerLoan>`
    - `suspend fun borrow(player: Player, amount: Double)`
    - `suspend fun repay(player: Player, amount: Double)`
    - `suspend fun setPaymentAmount(player: Player, paymentAmount: Double?)`
    - `suspend fun logs(player: Player, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>>`
    - `suspend fun borrowLimit(player: Player): Result<Double>`
  - メッセージは `Messages` を使用。金額は `BalanceFormats` で整形。
  - 借入時は `paymentAmount` を、返済時は残額として `borrowAmount` を表示。

- 新規コマンド: `ServerLoanCommand`（`/mrevo`）
  - 位置: `src/main/java/red/man10/man10bank/command/serverloan/ServerLoanCommand.kt`
  - `BaseCommand` を継承。
    - 設定: `allowPlayer=true`, `allowConsole=false`, `allowGeneralUser=true`。
  - 実装済みサブコマンド:
    - `/mrevo`（引数なし）: 概要表示（`get` と `borrowLimit`）。クリックで`/mrevo help`表示。
    - `/mrevo borrow <金額>`: 借入
    - `/mrevo pay <金額>`: 返済
    - `/mrevo payment <金額>`: 支払額設定
    - `/mrevo log [ページ]`: ログ表示（ページ、1ページ10件）
    - `/mrevo help`: ヘルプ
  - 金額パース/検証は `parsePositiveAmountOrNotify()` に集約。
  - ログ日付整形は `DateFormats.fromIsoString()` を使用。
  - `logs` エイリアスは廃止し、`log` のみ対応。

- DI/配線
  - `plugin.yml` に `/mrevo` を追加。
  - `Man10Bank` にて `ServerLoanApiClient`/`ServerLoanService` を生成し、`/mrevo` を `ServerLoanCommand` に接続。
  - `ServerLoanService` コンストラクタは `(plugin, serverLoanApi)` に統一。

- 既存改善
  - `ChequeService.useCheque()` を `Result<Double>` 返却に変更。呼び出し側のメッセージ分岐を実装。

## 補足/設計メモ
- メッセージは全て `Messages` 経由で送信。
- コマンドは Console 非対応（BaseCommand設定）。`sender as Player` は `execute()` 冒頭で一度だけキャスト。
- `plugin.yml` の `mrevo` permission は現在 `mrevo`（default: op）。一般ユーザーへ開放するなら `man10bank.user` へ変更検討。

## 今後のTODO候補
- APIエラー文言の日本語化/詳細化。
- `/mrevo` 概要の情報追加（停止利子/失敗回数など）。
- ログ表示のアクション名の日本語化。