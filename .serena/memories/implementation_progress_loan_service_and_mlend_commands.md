# LoanService 呼び出し元と /mlend コマンド拡張の実装記録

## 変更サマリ
- LoanService
  - create 成功時に issueDebtNote で債権者へ手形を発行（溢れはドロップ）。
  - PlayerInteractEvent で手形右クリックを検知し repay を実行。
  - repay 完了後に Loan を再取得し、issueDebtNote で手形を更新（使用手形は消費）。
  - releaseCollateral（借り手）: API成否〜UI表示までサービス層で処理（CollateralDebtorReleaseUI を開く）。
  - getBorrowerLoans: APIの成功/失敗を内部で処理し、失敗時は日本語メッセージ + 空リストを返却。
- LendCommand
  - /mlend list: 借り手視点の未返済/担保未回収一覧を表示（サービスが返す List<Loan> をそのまま整形）。
  - /mlend release <id>: releaseCollateral を呼ぶだけに簡素化（API処理は LoanService へ集約）。
  - 既存フローの細かい修正（最終承認時の提案削除タイミング、重複承認防止 等）。
- その他
  - Man10Bank -> LoanService へ CoroutineScope 注入。
  - DateFormats 整理（toDateTime/toDate 追加）と ATM/ServerLoan の表示統一。
  - 手形ロアの表示調整（期限は日付のみ、担保有無の行を追加）。

## 主要ファイル
- src/main/java/red/man10/man10bank/service/LoanService.kt
- src/main/java/red/man10/man10bank/command/loan/LendCommand.kt
- src/main/java/red/man10/man10bank/Man10Bank.kt
- src/main/java/red/man10/man10bank/util/DateFormats.kt
- src/main/java/red/man10/man10bank/command/atm/AtmCommand.kt
- src/main/java/red/man10/man10bank/command/serverloan/ServerLoanCommand.kt

## 動作メモ
- 手形識別: PDC `loan_id`（旧互換: `id`）。
- 右クリックで返済/担保回収（GUI）を起動。返済後は手形を更新。
- 借り手向け: `/mlend list`, `/mlend release <id>` を案内可能。

## 次の候補
- 完済時の手形取り扱いポリシー検討（自動破棄/履歴用保持など）。
- 返済状態や担保状態のフィルタを API 側仕様に合わせ詳細化。
- MockBukkit によるイベント/GUIの簡易テスト。