package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import red.man10.man10bank.api.model.response.ServerEstateHistory

/** /api/ServerEstate 用のAPIクライアント */
class ServerEstateApiClient(private val client: HttpClient) {

    suspend fun history(limit: Int = 100, offset: Int = 0): Result<List<ServerEstateHistory>> = runCatching {
        client.get("/api/ServerEstate/history") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.body()
    }
}

