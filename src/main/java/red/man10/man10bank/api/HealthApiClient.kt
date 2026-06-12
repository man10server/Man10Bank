package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.response.HealthPayload

/** /api/Health 用のAPIクライアント */
class HealthApiClient(private val client: HttpClient) {
    suspend fun get(): Result<HealthPayload> = client.getJson("/api/Health")
}
