コーディング規約
- 言語: Kotlin。インデント4スペース。開き波括弧は同一行。
- パッケージ: 小文字 (例: red.man10.man10bank)
- クラス/オブジェクト: パスカルケース (例: Man10Bank)
- 関数/プロパティ: キャメルケース。定数は UPPER_SNAKE_CASE (`const val`)
- ファイル配置: Kotlinファイルは src/main/java 配下 (Gradle設定済み)

メッセージ/コメント (日本語徹底)
- コミット、ソースコメント、ログ/GUI/チャット等は日本語
- 例: `Messages.error(sender, "このコマンドはプレイヤーのみ使用できます。")`

UI規約
- `InventoryUI` は `open class`。`fillBackground(ItemStack)` で背景一括設定可。
- クリックイベントは `UIService` から `InventoryUI` にディスパッチ。トップインベントリ操作はキャンセル。
- 戻る画面: `InventoryUI` の `previousUI` でプレイヤークローズ時に戻れる。

PR/コミット
- コミットは簡潔な常態述語・日本語。プレフィックス例: feat:, fix:, refactor:, test:, build:
- PR要件: 変更概要/目的、日本語説明、build/testグリーン、設定/UI/出力変更の影響範囲/スクショ等を明記
