package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.api.model.response.LoanRepayResponse
import java.util.UUID
import red.man10.man10bank.api.error.ApiErrorHandler

/** /api/Loan 用のAPIクライアント */
class LoanApiClient(private val client: HttpClient) {

    suspend fun getBorrowerLoans(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<Loan>> =
        ApiErrorHandler.run {
            client.get("/api/Loan/borrower/${uuid}") {
                if (limit >= 0) parameter("limit", limit)
                if (offset >= 0) parameter("offset", offset)
            }.body()
        }

    suspend fun create(body: LoanCreateRequest): Result<Loan> = ApiErrorHandler.run {
        client.post("/api/Loan") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun get(id: Int): Result<Loan> = ApiErrorHandler.run { client.get("/api/Loan/${id}").body() }

    suspend fun repay(id: Int, collectorUuid: String?): Result<LoanRepayResponse> = ApiErrorHandler.run {
        client.post("/api/Loan/${id}/repay") {
            collectorUuid?.let { parameter("collectorUuid", it) }
        }.body()
    }

    suspend fun releaseCollateral(id: Int, borrowerUuid: String?): Result<Loan> = ApiErrorHandler.run {
        client.post("/api/Loan/${id}/collateral/release") {
            borrowerUuid?.let { parameter("borrowerUuid", it) }
        }.body()
    }
}
