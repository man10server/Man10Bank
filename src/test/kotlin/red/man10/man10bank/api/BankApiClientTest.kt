package red.man10.man10bank.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.error.IndeterminateOutcomeException
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * BankApiClient のテスト（Ktor MockEngine）。
 * - BalanceResponse の一発デコード（balance取得 / 入出金 / transfer）。
 * - transfer の 409(InsufficientFunds) が Result.failure になり code を取得できること。
 * - requestId(冪等キー)が本文に含まれ、結果不確定の失敗に限って同一 requestId で再送されること。
 */
@DisplayName("BankApiClient のテスト")
class BankApiClientTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun config(): ApiConfig =
        ApiConfig(
            baseUrl = "http://localhost",
            apiKey = null,
            timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
            retries = 0,
        )

    // balance を返す共通のMockEngineを生成する。
    private fun balanceEngine(balance: Double) = MockEngine {
        respond(
            content = "{\"balance\":$balance}",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    private fun okBalance(balance: Double) = Pair(
        "{\"balance\":$balance}",
        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun depositRequest() = DepositRequest(
        uuid = uuid.toString(), amount = 100.0, pluginName = "test",
        note = "note", displayNote = "表示", server = "srv", requestId = BankApiClient.newRequestId(),
    )

    private fun withdrawRequest() = WithdrawRequest(
        uuid = uuid.toString(), amount = 100.0, pluginName = "test",
        note = "note", displayNote = "表示", server = "srv", requestId = BankApiClient.newRequestId(),
    )

    private fun transferRequest() = TransferRequest(
        fromUuid = uuid.toString(), toUuid = "00000000-0000-0000-0000-000000000002",
        amount = 100.0, pluginName = "test", note = "note", displayNote = "表示", server = "srv",
        requestId = BankApiClient.newRequestId(),
    )

    @Test
    @DisplayName("getBalanceがBalanceResponseをデコードしDoubleを返す")
    fun getBalanceDecodesBalanceResponse() = runBlocking {
        val client = HttpClientFactory.create(config(), balanceEngine(1234.5))
        client.use {
            val result = BankApiClient(it).getBalance(uuid)
            assertTrue(result.isSuccess)
            assertEquals(1234.5, result.getOrNull())
        }
    }

    @Test
    @DisplayName("depositが操作後残高をDoubleで返す")
    fun depositReturnsBalance() = runBlocking {
        val client = HttpClientFactory.create(config(), balanceEngine(500.0))
        client.use {
            val result = BankApiClient(it).deposit(depositRequest())
            assertTrue(result.isSuccess)
            assertEquals(500.0, result.getOrNull())
        }
    }

    @Test
    @DisplayName("withdrawが操作後残高をDoubleで返す")
    fun withdrawReturnsBalance() = runBlocking {
        val client = HttpClientFactory.create(config(), balanceEngine(300.0))
        client.use {
            val result = BankApiClient(it).withdraw(withdrawRequest())
            assertTrue(result.isSuccess)
            assertEquals(300.0, result.getOrNull())
        }
    }

    @Test
    @DisplayName("transferが成功時に送金元の新残高をDoubleで返す")
    fun transferReturnsFromBalance() = runBlocking {
        val client = HttpClientFactory.create(config(), balanceEngine(900.0))
        client.use {
            val result = BankApiClient(it).transfer(transferRequest())
            assertTrue(result.isSuccess)
            assertEquals(900.0, result.getOrNull())
        }
    }

    @Test
    @DisplayName("transferの409(InsufficientFunds)がResult.failureになりcodeを取得できる")
    fun transferInsufficientFundsBecomesFailure() = runBlocking {
        val body = "{\"title\":\"残高が不足しています\",\"status\":409,\"code\":\"InsufficientFunds\"}"
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClientFactory.create(config(), engine)
        client.use {
            val result = BankApiClient(it).transfer(transferRequest())
            assertTrue(result.isFailure, "409は失敗として伝播する")
            val ex = result.exceptionOrNull()
            assertTrue(ex is ApiHttpException, "ApiHttpExceptionへ正規化される")
            ex as ApiHttpException
            assertEquals(HttpStatusCode.Conflict, ex.status)
            assertEquals("InsufficientFunds", ex.code, "code でエラー種別を判定できる")
        }
    }

    // ---------------------------------------------------------------
    // 冪等キー(requestId)と結果不確定時の再送
    // ---------------------------------------------------------------

    @Test
    @DisplayName("requestIdがリクエスト本文に含まれる")
    fun requestIdIsSentInBody() = runBlocking {
        var sentBody = ""
        val engine = MockEngine { request ->
            sentBody = request.body.toByteArray().decodeToString()
            val (content, headers) = okBalance(100.0)
            respond(content, HttpStatusCode.OK, headers)
        }
        val req = depositRequest()
        HttpClientFactory.create(config(), engine).use {
            assertTrue(BankApiClient(it).deposit(req).isSuccess)
        }
        assertTrue(sentBody.contains("\"requestId\":\"${req.requestId}\""), "本文に requestId が含まれる: $sentBody")
    }

    @Test
    @DisplayName("リクエストタイムアウト後の再送は同一requestIdで行われ、成功すれば成功として返る")
    fun depositRetriesTimeoutWithSameRequestId() = runBlocking {
        val count = AtomicInteger(0)
        val requestIds = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            requestIds += Regex("\"requestId\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            if (count.incrementAndGet() == 1) {
                // 画面に出ていたものと同じ Ktor のリクエストタイムアウト
                throw HttpRequestTimeoutException("http://localhost/api/Bank/deposit", 10_000)
            }
            val (content, headers) = okBalance(500.0)
            respond(content, HttpStatusCode.OK, headers)
        }
        val req = depositRequest()
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 2, retryDelayMs = 0).deposit(req)
            assertTrue(result.isSuccess, "再送で成功すれば成功として返る")
            assertEquals(500.0, result.getOrNull())
        }
        assertEquals(2, count.get(), "初回 + 再送1回")
        assertEquals(listOf(req.requestId, req.requestId), requestIds, "再送は同一 requestId で行われる")
    }

    @Test
    @DisplayName("確定的な失敗(409)は再送されない")
    fun depositDoesNotRetryDefinitiveFailure() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            respond(
                "{\"title\":\"残高が不足しています\",\"status\":409,\"code\":\"InsufficientFunds\"}",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 2, retryDelayMs = 0).withdraw(withdrawRequest())
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ApiHttpException, "確定的な失敗はそのまま返る")
        }
        assertEquals(1, count.get(), "409 は再送しない")
    }

    @Test
    @DisplayName("500(UnexpectedError)はサーバーがロールバック済みの確定的な失敗なので再送されない")
    fun depositDoesNotRetryServerError() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            respond(
                "{\"title\":\"予期しないエラーが発生しました。\",\"status\":500,\"code\":\"UnexpectedError\"}",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 2, retryDelayMs = 0).deposit(depositRequest())
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ApiHttpException)
        }
        assertEquals(1, count.get())
    }

    @Test
    @DisplayName("中継障害(503)は結果不確定として再送され、成功すれば成功として返る")
    fun depositRetriesOnBadGatewayThenSucceeds() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            if (count.incrementAndGet() == 1) {
                respond("Service Unavailable", HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            } else {
                val (content, headers) = okBalance(700.0)
                respond(content, HttpStatusCode.OK, headers)
            }
        }
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 2, retryDelayMs = 0).transfer(transferRequest())
            assertTrue(result.isSuccess)
            assertEquals(700.0, result.getOrNull())
        }
        assertEquals(2, count.get())
    }

    @Test
    @DisplayName("I/O例外(接続断)は結果不確定として再送される")
    fun transferRetriesOnIoException() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            if (count.incrementAndGet() == 1) throw IOException("Connection reset")
            val (content, headers) = okBalance(900.0)
            respond(content, HttpStatusCode.OK, headers)
        }
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 1, retryDelayMs = 0).transfer(transferRequest())
            assertTrue(result.isSuccess)
        }
        assertEquals(2, count.get())
    }

    @Test
    @DisplayName("全試行がタイムアウトした場合はIndeterminateOutcomeException(結果不確定)で返り、試行回数とrequestIdを持つ")
    fun depositReturnsIndeterminateWhenAllAttemptsTimeOut() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            throw SocketTimeoutException("Read timed out")
        }
        val req = depositRequest()
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 2, retryDelayMs = 0).deposit(req)
            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertTrue(ex is IndeterminateOutcomeException, "確定しなければ IndeterminateOutcomeException: $ex")
            ex as IndeterminateOutcomeException
            assertEquals(req.requestId, ex.requestId)
            assertEquals(3, ex.attempts, "初回 + 再送2回")
            assertTrue(ex.cause is SocketTimeoutException, "最後の原因を保持する")
        }
        assertEquals(3, count.get())
    }

    @Test
    @DisplayName("maxRetries=0 では再送せず1回で結果不確定になる")
    fun noRetryWhenMaxRetriesIsZero() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            throw HttpRequestTimeoutException("http://localhost/api/Bank/withdraw", 10_000)
        }
        HttpClientFactory.create(config(), engine).use {
            val result = BankApiClient(it, maxRetries = 0, retryDelayMs = 0).withdraw(withdrawRequest())
            val ex = result.exceptionOrNull()
            assertTrue(ex is IndeterminateOutcomeException)
            assertEquals(1, (ex as IndeterminateOutcomeException).attempts)
        }
        assertEquals(1, count.get())
    }
}
