package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import red.man10.man10bank.api.model.LoanCreateRequest
import java.util.UUID

/** /api/Loan 用のAPIクライアント */
class LoanApiClient(private val client: HttpClient) {

    suspend fun getBorrowerLoans(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<String> = runCatching {
        client.get("/api/Loan/borrower/${uuid}") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.bodyAsText()
    }

    suspend fun create(body: LoanCreateRequest): Result<Unit> = runCatching {
        client.post("/api/Loan") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend fun get(id: Int): Result<String> = runCatching {
        client.get("/api/Loan/${id}").bodyAsText()
    }

    suspend fun repay(id: Int, collectorUuid: String?): Result<Unit> = runCatching {
        client.post("/api/Loan/${id}/repay") {
            collectorUuid?.let { parameter("collectorUuid", it) }
        }
        Unit
    }

    suspend fun releaseCollateral(id: Int, borrowerUuid: String?): Result<Unit> = runCatching {
        client.post("/api/Loan/${id}/collateral/release") {
            borrowerUuid?.let { parameter("borrowerUuid", it) }
        }
        Unit
    }
}

