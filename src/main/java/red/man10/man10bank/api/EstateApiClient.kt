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
import red.man10.man10bank.api.model.request.EstateUpdateRequest
import red.man10.man10bank.api.model.response.Estate
import red.man10.man10bank.api.model.response.EstateHistory
import java.util.UUID

/** /api/Estate 用のAPIクライアント */
class EstateApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<Estate> = runCatching { client.get("/api/Estate/${uuid}").body() }

    suspend fun history(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<EstateHistory>> = runCatching {
        client.get("/api/Estate/${uuid}/history") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.body()
    }

    suspend fun snapshot(uuid: UUID, body: EstateUpdateRequest): Result<Boolean> = runCatching {
        client.post("/api/Estate/${uuid}/snapshot") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun ranking(limit: Int = 100, offset: Int = 0): Result<List<Estate>> = runCatching {
        client.get("/api/Estate/ranking") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.body()
    }
}
