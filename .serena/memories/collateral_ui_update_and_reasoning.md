# 担保UIの仕様更新と設計メモ

## 仕様更新（UI）
- レイアウト: 1行9スロット
  - スロット0〜7: 担保アイテム領域（editable=true時のみ編集可）
  - スロット8: 閉じるボタン（§c§l閉じる）
- fillerは不使用

## 実装（ファイル）
- CollateralBaseUI（基本）: `src/main/java/red/man10/man10bank/ui/loan/CollateralBaseUI.kt`
  - `getCollateralItems(): List<ItemStack>` を提供（非null/非Airのみcloneで返却）
  - `setCollateralItems(items: List<ItemStack?>)` で0〜7をクリアして先頭から詰めて配置
  - `setCollateralItemAt(slot, item)`/`isCollateralAreaEmpty()`/`open(player)` を提供
- 画面
  - 設定: `CollateralSetupUI`（クローズで `onUpdate(newItems: List<ItemStack>)`）
  - 確認: `CollateralViewUI`（参照のみ、編集不可）
  - 受け取り（債権者）: `CollateralCollectUI`（0〜7全て空で `onCollected()`）
  - 返還受け取り（債務者）: `CollateralDebtorReleaseUI`（0〜7全て空で `onReleased()`）

## setCollateralItems で Inventory#addItem を使わない理由
1. コントロールスロットの保護
   - スロット8は閉じるボタンで固定。`addItem` は自動配置のため、将来的にレイアウト変更があった場合の安全性を優先し明示配置を選択。
2. 並び順とスタックの予測可能性
   - `addItem` は自動スタック/分割が起きるため、渡されたリストの順序・個数を厳密に保つ目的と相性が悪い。
3. クリアと上書きの明確さ
   - 0〜7を一度クリアしてから配置することで、状態遷移が決定論的になる。
4. 余剰ハンドリングの明確化
   - 8個超過分をアプリ側で明示的に扱える（警告・切り捨てなど）。

必要であれば `addItem` ベースに差し替え可能（余剰は戻り値で検知）。現状は順序・個数の厳密性を優先。