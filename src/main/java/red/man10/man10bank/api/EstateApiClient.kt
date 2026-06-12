package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.EstateUpdateRequest
import red.man10.man10bank.api.model.response.Estate
import red.man10.man10bank.api.model.response.EstateHistory
import java.util.UUID

/** /api/Estate 用のAPIクライアント */
class EstateApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<Estate> = client.getJson("/api/Estate/${uuid}")

    suspend fun history(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<EstateHistory>> =
        client.getJson("/api/Estate/${uuid}/history") { paging(limit, offset) }

    suspend fun snapshot(uuid: UUID, body: EstateUpdateRequest): Result<Boolean> =
        client.postJson("/api/Estate/${uuid}/snapshot", body)

    suspend fun ranking(limit: Int = 100, offset: Int = 0): Result<List<Estate>> =
        client.getJson("/api/Estate/ranking") { paging(limit, offset) }
}
