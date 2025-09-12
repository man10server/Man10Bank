package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import red.man10.man10bank.api.model.response.HealthPayload

/** /api/Health 用のAPIクライアント */
class HealthApiClient(private val client: HttpClient) {
    suspend fun get(): Result<HealthPayload> = runCatching {
        client.get("/api/Health").body()
    }
}

