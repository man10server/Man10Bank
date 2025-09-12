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
    suspend fun buildHealthMessage(): Result<String> = client.get().map { h ->
        """
            [HealthCheck]
            service: ${h.service}
            db: ${h.database}
            uptime: ${h.uptimeSeconds}s
            serverTimeUtc: ${h.serverTimeUtc}
            startedAtUtc: ${h.startedAtUtc}
        """.trimIndent()
    }
}
