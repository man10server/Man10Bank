# 実装進捗サマリ（ServerLoan + BankService + 残高表示）

## ServerLoan（/mrevo）
- サービス: `ServerLoanService`
  - 借入/返済/支払額設定/ログ/上限/取得/支払情報(payment-info) 実装（プレイヤー対象の便宜メソッドを含む）。
  - メッセージは `Messages` 使用、金額は `BalanceFormats`、日付は `DateFormats`。
- コマンド: `ServerLoanCommand`（/mrevo）
  - 概要表示（借入額/支払額/最終返済/借入上限/次回返済日/1日利息）+ ヘルプボタン
  - borrow/pay/payment/log（ページ）/help 実装。金額パース関数を共通化。
- API: `ServerLoanApiClient` に `paymentInfo(uuid)` を追加、モデル `PaymentInfoResponse` を新規追加。

## Cheque（小切手）
- `ChequeService.useCheque()` を `Result<Double>` 返却に修正し、呼び出し側で成功/失敗メッセージ分岐。

## Loan（プレイヤー間ローン）
- サービス: `LoanService`（Listener実装・イベント登録済、型→API接続まで）
  - `getBorrowerLoans/get/create/repay/releaseCollateral`（create は 現在UTC+n日で期限を算出、repay/解放の引数を必須化）。
- Util: `ItemStackBase64` を追加（YAMLシリアライズ経由で Base64 変換、非推奨のJava直列化は不使用）。

## 残高表示（/bal 等）
- `BalanceRegistry` を Context-less に刷新（`suspend fun line(player)`）。
- 各実装側でプロバイダ登録する方針に変更：
  - `CashItemManager.registerBalanceProvider()`（現金: インベントリ+エンダーチェスト）
  - `VaultManager.registerBalanceProvider()`（電子マネー）
  - `BankService.registerBalanceProvider()`（銀行）
- `BalanceProviders` は廃止。`BalanceCommand` は依存削減（plugin/scopeのみ）。
- Cash 集計関数を追加: `countInventoryCash/countEnderChestCash/countTotalCash`。

## Bank（入出金/送金/金額解決）
- 新サービス: `BankService`
  - 残高プロバイダ登録
  - `deposit/withdraw/transfer` を実装（メッセージ送信、ロールバック含む）
  - 金額解決: `resolveDepositAmount(player, arg?)`（未指定/ALLで全額, Vaultベース）
    `resolveWithdrawAmount(player, arg?)`（未指定/ALLで全額, 銀行ベース）
  - `getBalance(player)` 便宜関数
  - リクエスト生成を共通化: `buildDepositRequest/buildWithdrawRequest`（Player, amount, note, displayNote を受け取る）
- コマンド更新
  - `/deposit`: 引数なし=ALL。金額解決と処理を `BankService` に委譲。
  - `/withdraw`: 引数なし=ALL。金額解決と処理を `BankService` に委譲。
  - `/mpay`: 二重実行の確認後、`BankService.transfer` を呼び出すのみ。

## DI/配線
- `Man10Bank`
  - `BankService`, `LoanService`, `ServerLoanService` を生成。
  - イベント登録: `loanService` を追加。
  - 残高プロバイダ登録: `cashItemManager`, `vaultManager`, `bankService` から登録。
  - コマンド配線更新: `/deposit`, `/withdraw`, `/mpay`, `/mrevo`。

## 補足
- すべてのユーザー向けメッセージは日本語で統一。
- 可能な範囲で例外→Result化を維持し、UI層でメッセージ化。