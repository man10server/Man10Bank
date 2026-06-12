package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.AtmLogRequest
import red.man10.man10bank.api.model.response.AtmLog
import java.util.UUID

/** /api/Atm 用のAPIクライアント */
class AtmApiClient(private val client: HttpClient) {

    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<AtmLog>> =
        client.getJson("/api/Atm/${uuid}/logs") { paging(limit, offset) }

    suspend fun appendLog(body: AtmLogRequest): Result<AtmLog> =
        client.postJson("/api/Atm/logs", body)
}
