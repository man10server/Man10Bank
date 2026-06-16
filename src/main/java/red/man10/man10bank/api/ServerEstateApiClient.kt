package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.response.ServerEstateHistory

/** /api/ServerEstate 用のAPIクライアント */
class ServerEstateApiClient(private val client: HttpClient) {

    suspend fun history(limit: Int = 100, offset: Int = 0): Result<List<ServerEstateHistory>> =
        client.getJson("/api/ServerEstate/history") { paging(limit, offset) }
}
