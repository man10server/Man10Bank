ビルド/配布
- gradle clean build  # シャドウJarを作成 (build/libs/Man10Bank-<version>.jar)
- gradle test         # JUnit Platform でテスト
- sh ./local-deploy.sh  # Paper サーバーの plugins/ へコピー
- gradle deploy -Pdeploy  # ビルド後に配布スクリプトを実行 (または DEPLOY_ON_BUILD=true)

ラン/検証 (Paperサーバー上)
- /deposit <amount|all>
- /withdraw <amount|all>
- /mpay <player> <amount>
- /bal (別名: /balance, /money, /bank)
- /atm  # ATM GUI を開く
- /bankop ...  # 管理向け

開発ユーティリティ (Darwin/macOS)
- git status / git diff / git add -p / git commit -m "..."
- rg "pattern" -n  # ripgrepで高速検索
- ./gradlew tasks  # Gradleタスク一覧

テスト
- gradle test  # MockBukkit 等を用いたユニット/結合テスト
