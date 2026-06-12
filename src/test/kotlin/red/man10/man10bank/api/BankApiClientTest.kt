package red.man10.man10bank.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.UUID

/**
 * BankApiClient のテスト（Ktor MockEngine）。
 * - BalanceResponse の一発デコード（balance取得 / 入出金 / transfer）。
 * - transfer の 409(InsufficientFunds) が Result.failure になり code を取得できること。
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

    private fun depositRequest() = DepositRequest(
        uuid = uuid.toString(), amount = 100.0, pluginName = "test",
        note = "note", displayNote = "表示", server = "srv",
    )

    private fun withdrawRequest() = WithdrawRequest(
        uuid = uuid.toString(), amount = 100.0, pluginName = "test",
        note = "note", displayNote = "表示", server = "srv",
    )

    private fun transferRequest() = TransferRequest(
        fromUuid = uuid.toString(), toUuid = "00000000-0000-0000-0000-000000000002",
        amount = 100.0, pluginName = "test", note = "note", displayNote = "表示", server = "srv",
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
}
