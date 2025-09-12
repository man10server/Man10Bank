package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import red.man10.man10bank.api.model.ChequeCreateRequest
import red.man10.man10bank.api.model.ChequeUseRequest

/** /api/Cheques 用のAPIクライアント */
class ChequesApiClient(private val client: HttpClient) {

    suspend fun create(body: ChequeCreateRequest): Result<Unit> = runCatching {
        client.post("/api/Cheques") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend fun get(id: Int): Result<String> = runCatching {
        client.get("/api/Cheques/${id}").bodyAsText()
    }

    suspend fun use(id: Int, body: ChequeUseRequest): Result<Unit> = runCatching {
        client.post("/api/Cheques/${id}/use") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }
}

