概要
- プロジェクト: Man10Bank (Minecraft Paper 用銀行プラグイン)
- 目的: 銀行APIとVault(経済)を連携し、入出金/送金/残高確認、ATM GUI などの機能を提供する。
- 主要機能: /deposit, /withdraw, /mpay, 残高表示(/bal 等), /atm でATM GUI、管理用 /bankop。
- 依存/統合: Vault(Economy), Paper API 1.21.x, 外部Bank API(HTTP; Ktor クライアント)。

技術スタック
- 言語: Kotlin 2.0.x (JDK 21)
- ビルド: Gradle + ShadowJar
- ライブラリ: Paper API, VaultAPI, kotlinx-coroutines, Ktor client, kotlinx-serialization-json, Adventure(Component)
- テスト: JUnit 5 + kotlin-test + MockBukkit

構成
- ソース: src/main/java (Kotlin。UI, command, service, api など)
- リソース: src/main/resources (plugin.yml, config.yml 等)
- テスト: src/test/kotlin
- 画像: images/

主なパッケージ
- red.man10.man10bank (メイン: Man10Bank)
- .../ui (InventoryUI, UIButton, UIService), .../ui/atm (AtmMainUI)
- .../service (VaultManager, CashItemManager, CashExchangeService, HealthService)
- .../command (balance, transaction, atm, op)
- .../api (BankApiClient, HealthApiClient など)

エントリポイント/コマンド
- JavaPlugin: red.man10.man10bank.Man10Bank
- コマンド: deposit, withdraw, mpay, bal/balance/money/bank, atm, bankop

補足
- 環境変数/プロパティで自動配布サポート: DEPLOY_ON_BUILD=true or -Pdeploy
- サーバー名は config.yml の serverName が空の場合は Bukkit サーバー名を使用