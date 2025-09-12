package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import red.man10.man10bank.api.model.EstateUpdateRequest
import java.util.UUID

/** /api/Estate 用のAPIクライアント */
class EstateApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<String> = runCatching {
        client.get("/api/Estate/${uuid}").bodyAsText()
    }

    suspend fun history(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<String> = runCatching {
        client.get("/api/Estate/${uuid}/history") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.bodyAsText()
    }

    suspend fun snapshot(uuid: UUID, body: EstateUpdateRequest): Result<Unit> = runCatching {
        client.post("/api/Estate/${uuid}/snapshot") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }
}

