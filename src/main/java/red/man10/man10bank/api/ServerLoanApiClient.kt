package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import red.man10.man10bank.api.model.ServerLoanBorrowBodyRequest
import java.util.UUID

/** /api/ServerLoan 用のAPIクライアント */
class ServerLoanApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<String> = runCatching {
        client.get("/api/ServerLoan/${uuid}").bodyAsText()
    }

    suspend fun borrow(uuid: UUID, body: ServerLoanBorrowBodyRequest): Result<Unit> = runCatching {
        client.post("/api/ServerLoan/${uuid}/borrow") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    suspend fun repay(uuid: UUID, amount: Double?): Result<Unit> = runCatching {
        client.post("/api/ServerLoan/${uuid}/repay") {
            amount?.let { parameter("amount", it) }
        }
        Unit
    }

    suspend fun setPaymentAmount(uuid: UUID, paymentAmount: Double?): Result<Unit> = runCatching {
        client.post("/api/ServerLoan/${uuid}/payment-amount") {
            paymentAmount?.let { parameter("paymentAmount", it) }
        }
        Unit
    }

    suspend fun borrowLimit(uuid: UUID): Result<String> = runCatching {
        client.get("/api/ServerLoan/${uuid}/borrow-limit").bodyAsText()
    }

    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<String> = runCatching {
        client.get("/api/ServerLoan/${uuid}/logs") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.bodyAsText()
    }
}

