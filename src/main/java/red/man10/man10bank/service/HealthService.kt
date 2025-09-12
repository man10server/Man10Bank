package red.man10.man10bank.service

import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.api.model.response.HealthPayload

/**
 * ヘルスチェック用サービス。
 * - シンプルな用途のため、インターフェイスは設けず具象クラスのみとします。
 */
class HealthService(
    private val client: HealthApiClient
) {
    /** ヘルス情報を取得します。 */
    suspend fun getHealth(): Result<HealthPayload> = client.get()

    /**
     * ヘルス情報を取得し、カラー付きの複数行メッセージに整形して返します。
     */
    suspend fun buildHealthMessage(): String {
        val result = getHealth()
        val h = result.getOrNull()
        if (h == null) {
            val errorMessage = result.exceptionOrNull()?.message ?: "不明なエラー"
            return "§c[ヘルスチェック] エラー: $errorMessage"
        } else {
            val dbStatus = if (h.database) "§aOK" else "§cFAIL"
            return """
                
                §6[ヘルスチェック]
                §eサービス: §a${h.service}
                §eデータベース: $dbStatus
                §e稼働時間: §a${h.uptimeSeconds}秒
                §eサーバー時間Utc: §a${h.serverTimeUtc}
                §e起動時間Utc: §a${h.startedAtUtc}
            """.trimIndent()
        }
    }
}
