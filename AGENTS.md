# リポジトリガイドライン

## プロジェクト構成
- ソース: `src/main/java`（Kotlinソース）。リソースは `src/main/resources`（`plugin.yml`, `config.yml`）。
- テスト: `src/test/kotlin`（JUnit 5 / Kotlin Test）。パッケージは `red.man10.man10bank` 配下に作成。
- 画像・資料: `images/`。ビルド成果物は `build/`。

## ビルド・テスト・開発コマンド
- シェードJarビルド: `gradle clean build`（`build/libs/Man10Bank-<version>.jar` を生成）。
- テスト実行: `gradle test`（JUnit Platform）。
- ローカル配布: `sh ./local-deploy.sh`（Paper サーバーの `plugins/` へコピー）。
- Gradle配布タスク: `gradle deploy`（ビルド後にスクリプト呼び出し）。
- 補足: ツールチェーンは JDK 21。自動配布は `-Pdeploy` または `DEPLOY_ON_BUILD=true` を使用。

## コーディング規約・命名
- 言語: Kotlin。4スペースインデント、同一行に開き波括弧。
- パッケージ: 小文字（例: `red.man10.man10bank`）。
- クラス/オブジェクト: パスカルケース（例: `Man10Bank`）。
- 関数/プロパティ: キャメルケース、定数は `UPPER_SNAKE_CASE`（`const val`）。
- 配置: Kotlinファイルは本リポジトリでは `src/main/java` に配置（Gradle設定済み）。

## テスト方針
- 使用: JUnit 5 + Kotlin Test。隔離用に H2 / MockBukkit を使用可能。
- 位置/命名: `src/test/kotlin/...` に `*Test.kt`。必要に応じて `@DisplayName`（日本語）を付与。
- 実行: `gradle test`。外部依存を避け、サービス/コマンドの振る舞いを重点的に検証。

## コミット・PR運用
- コミットは簡潔な常態述語で日本語記述。接頭辞例: `feat:`, `fix:`, `refactor:`, `test:`, `build:`（任意スコープ: `refactor(service): ...`）。
- PR要件:
  - 変更概要と目的（日本語）、関連Issueのリンク。
  - `gradle build` がグリーン、必要なテストを追加/更新。
  - `config.yml`/DB 挙動変更時は設定差分や影響範囲を明記。UI/出力変更はスクリーンショット/ログを添付。

## コミュニケーションルール（日本語徹底）
- Gitのコミットメッセージ、ソースコードのコメント、ログ/GUI/チャット等の表示メッセージは原則すべて日本語で記述してください。
- 例（コミット）: `fix: 送金額が負数の場合に拒否する`
- 例（コメント）: `// 口座残高を更新し、失敗時は補償で戻す`
- 例（表示）: `player.sendMessage("残高が不足しています")`

## セキュリティ・設定
- 秘密情報をコミットしないこと。.env を使用（例: `DEPLOY_DIR=/path/to/paper/plugins`）。
- DB認証情報は `config.yml` から読み込み。初期値や変更点はPRで説明すること。
