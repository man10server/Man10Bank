package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.BorrowLimitResponse
import red.man10.man10bank.api.model.response.PaymentInfoResponse
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanLog
import java.util.UUID

/** /api/ServerLoan 用のAPIクライアント */
class ServerLoanApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<ServerLoan> = client.getJson("/api/ServerLoan/${uuid}")

    suspend fun borrow(uuid: UUID, body: ServerLoanBorrowBodyRequest): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/borrow", body)

    suspend fun repay(uuid: UUID, amount: Double?): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/repay") {
            amount?.let { parameter("amount", it) }
        }

    suspend fun setPaymentAmount(uuid: UUID, paymentAmount: Double?): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/payment-amount") {
            paymentAmount?.let { parameter("paymentAmount", it) }
        }

    suspend fun setBorrowAmount(uuid: UUID, amount: Double): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/borrow-amount") {
            parameter("amount", amount)
        }

    suspend fun borrowLimit(uuid: UUID): Result<Double> =
        client.getJson<BorrowLimitResponse>("/api/ServerLoan/${uuid}/borrow-limit").map { it.limit }

    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>> =
        client.getJson("/api/ServerLoan/${uuid}/logs") { paging(limit, offset) }

    suspend fun paymentInfo(uuid: UUID): Result<PaymentInfoResponse> =
        client.getJson("/api/ServerLoan/${uuid}/payment-info")
}
