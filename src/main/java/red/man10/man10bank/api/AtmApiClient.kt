package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import red.man10.man10bank.api.model.AtmLogRequest
import red.man10.man10bank.api.model.AtmLog
import java.util.UUID

/** /api/Atm 用のAPIクライアント */
class AtmApiClient(private val client: HttpClient) {

    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<AtmLog>> = runCatching {
        client.get("/api/Atm/${uuid}/logs") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.body()
    }

    suspend fun appendLog(body: AtmLogRequest): Result<AtmLog> = runCatching {
        client.post("/api/Atm/logs") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }
}
