package red.man10.man10bank.service

import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.api.model.response.HealthPayload
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.util.errorMessage

/**
 * ヘルスチェック用サービス。
 * - シンプルな用途のため、インターフェイスは設けず具象クラスのみとします。
 */
class HealthService(
    private val client: HealthApiClient,
    private val apiConfig: ApiConfig,
) {
    /** ヘルス情報を取得します。 */
    suspend fun getHealth(): Result<HealthPayload> = client.get()

    /**
     * ヘルス情報を取得し、カラー付きの複数行メッセージに整形して返します。
     * - WebAPIの状態に加え、認証/接続設定（接続先URL・APIキーの有無）も表示します。
     *   401 Unauthorized など認証起因の不具合を切り分けやすくするためです。
     */
    suspend fun buildHealthMessage(): String {
        val lines = mutableListOf("", "§6[ヘルスチェック]")

        val result = getHealth()
        val h = result.getOrNull()
        if (h == null) {
            lines += "§c接続エラー: ${result.errorMessage()}"
        } else {
            val dbStatus = if (h.database) "§aOK" else "§cFAIL"
            lines += "§eサービス: §a${h.service}"
            lines += "§eデータベース: $dbStatus"
            lines += "§e稼働時間: §a${h.uptimeSeconds}秒"
            lines += "§eサーバー時間Utc: §a${h.serverTimeUtc}"
            lines += "§e起動時間Utc: §a${h.startedAtUtc}"
        }

        // 認証/接続設定（APIの応答可否に関わらず常に表示する）
        lines += "§e接続先URL: §a${apiConfig.baseUrl}"
        lines += "§eAPIキー: ${apiKeyStatus()}"

        return lines.joinToString("\n")
    }

    /** APIキーの設定状態を、値を伏せた形で表示用に組み立てます。 */
    private fun apiKeyStatus(): String {
        val key = apiConfig.apiKey
        return if (key.isNullOrBlank()) {
            "§c未設定（認証ヘッダ無しで接続します）"
        } else {
            "§a設定済み §7(${maskApiKey(key)})"
        }
    }

    companion object {
        /**
         * APIキーをログ/画面表示用にマスクします。
         * - 秘密の漏洩を避けつつ、どのキーが読み込まれているか識別できるよう
         *   先頭と末尾の数文字のみ残し、中央を伏せ字にして長さを併記します。
         */
        internal fun maskApiKey(key: String): String {
            val len = key.length
            val masked = if (len <= 4) {
                "*".repeat(len)
            } else {
                key.take(2) + "*".repeat((len - 4).coerceAtMost(8)) + key.takeLast(2)
            }
            return "$masked, 長さ$len"
        }
    }
}
