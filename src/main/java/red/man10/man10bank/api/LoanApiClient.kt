package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.api.model.response.LoanRepayResponse
import java.util.UUID

/** /api/Loan 用のAPIクライアント */
class LoanApiClient(private val client: HttpClient) {

    suspend fun getBorrowerLoans(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<Loan>> =
        client.getJson("/api/Loan/borrower/${uuid}") { paging(limit, offset) }

    suspend fun create(body: LoanCreateRequest): Result<Loan> =
        client.postJson("/api/Loan", body)

    suspend fun get(id: Int): Result<Loan> = client.getJson("/api/Loan/${id}")

    suspend fun repay(id: Int, collectorUuid: String?): Result<LoanRepayResponse> =
        client.postJson("/api/Loan/${id}/repay") {
            collectorUuid?.let { parameter("collectorUuid", it) }
        }

    suspend fun releaseCollateral(id: Int, borrowerUuid: String?): Result<Loan> =
        client.postJson("/api/Loan/${id}/collateral/release") {
            borrowerUuid?.let { parameter("borrowerUuid", it) }
        }
}
