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
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanLog
import java.util.UUID

/** /api/ServerLoan 用のAPIクライアント */
class ServerLoanApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<ServerLoan> = runCatching { client.get("/api/ServerLoan/${uuid}").body() }

    suspend fun borrow(uuid: UUID, body: ServerLoanBorrowBodyRequest): Result<ServerLoan> = runCatching {
        client.post("/api/ServerLoan/${uuid}/borrow") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun repay(uuid: UUID, amount: Double?): Result<ServerLoan> = runCatching {
        client.post("/api/ServerLoan/${uuid}/repay") {
            amount?.let { parameter("amount", it) }
        }.body()
    }

    suspend fun setPaymentAmount(uuid: UUID, paymentAmount: Double?): Result<ServerLoan> = runCatching {
        client.post("/api/ServerLoan/${uuid}/payment-amount") {
            paymentAmount?.let { parameter("paymentAmount", it) }
        }.body()
    }

    suspend fun borrowLimit(uuid: UUID): Result<Double> = runCatching { client.get("/api/ServerLoan/${uuid}/borrow-limit").body() }

    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>> = runCatching {
        client.get("/api/ServerLoan/${uuid}/logs") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.body()
    }
}
