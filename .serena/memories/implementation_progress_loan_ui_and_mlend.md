# 実装進捗（Loan UI/コマンド/ApiClient まで）

## 担保UI（loan/ui/loan）
- CollateralBaseUI: 1行9スロットに統一。0〜7=担保、8=閉じる。editable=false時はプレイヤーインベントリも操作不可。
- CollateralSetupUI: 既存担保リストを受け取り、クローズ時に List<ItemStack> を onUpdate で返却。UI上のアイテムは返却して消失防止。
- CollateralViewUI: 参照専用（editable=false）。
- CollateralCollectUI: 債権者の受け取り用。0〜7が空で onCollected。
- CollateralDebtorReleaseUI: 債務者の返還受け取り用。0〜7が空で onReleased。
- setCollateralItems/getCollateralItems を List 対応に統一（JVMシグネチャ衝突回避のためオーバーロードは1つに）。

## コマンド（/mlend）
- LendCommand 追加。提案→借り手操作（担保設定/承認/拒否）→貸し手確認（担保確認/最終承認/拒否）→最終承認で LoanService.create。
- 返済日は日数(Int)のみ。parsePaybackDays は廃止。
- 重複提案防止: existsActiveForBorrower(borrower) を追加。
- Proposal に isBorrower/isLender を追加し、チェック関数（isNotBorrower/isNotLender）を導入。
- 拒否時に returnCollateralsToBorrower() で担保返却（在庫に入らない分は自然落下）。
- Man10Bank に /mlend の executor 登録済み。plugin.yml は既定定義あり。

## LoanService / API 連携
- create: 担保を List<ItemStack>? に変更し、Base64は ItemStackBase64.encodeItems で1文字列に。
- issueDebtNote(loan: Loan): 借金手形発行を Loan 引数に変更。Loreは仕様に準拠。PDCは loan_id のみを設定。
- getLoanId(item): PDC から loan_id を取得（旧形式 id もサポート）。
- repay: openapi.json の更新に合わせて LoanRepayResponse を導入、ApiClient/Service の戻り型を変更。
- outcome=0=支払い回収、1=担保回収（CollateralCollectUI）、それ以外=エラー、404時は専用文言の実装（イベントハンドラ連携は後続で再調整）。

## ユーティリティ
- ItemStackBase64: encodeItems(List|vararg), decodeItems(String) を追加（複数担保対応）。

## 備考
- Collateral UI で Inventory#addItem を使わない理由（レイアウト保護/順序・個数の厳密性/決定論性/余剰ハンドリング）を別メモに記載（collateral_ui_update_and_reasoning）。
- 手形クリック時のrepay EventHandler は試作→一旦削除。以後の再実装時は getLoanId → repay を非同期で実行する方針。