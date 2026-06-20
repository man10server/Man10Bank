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
import red.man10.man10bank.api.model.request.VaultDepositRequest
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.UUID

@DisplayName("VaultApiClient のテスト")
class VaultApiClientTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val toUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    private fun config(): ApiConfig = ApiConfig(
        baseUrl = "http://localhost",
        apiKey = null,
        timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
        retries = 0,
    )

    private fun json(content: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine {
        respond(
            content = content,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    @Test
    @DisplayName("getBalance が balance/version をデコードする")
    fun getBalance() = runBlocking {
        val client = HttpClientFactory.create(config(), json("{\"balance\":1234,\"version\":7}"))
        client.use {
            val r = VaultApiClient(it).getBalance(uuid)
            assertTrue(r.isSuccess)
            assertEquals(1234L, r.getOrNull()?.balance)
            assertEquals(7L, r.getOrNull()?.version)
        }
    }

    @Test
    @DisplayName("getConfig が maxBalance/移動緩和設定をデコードする")
    fun getConfig() = runBlocking {
        val body = "{\"maxBalance\":1000000000000,\"joinReadyDelayMillis\":3000,\"quitDrainTimeoutMillis\":3000}"
        val client = HttpClientFactory.create(config(), json(body))
        client.use {
            val r = VaultApiClient(it).getConfig()
            assertTrue(r.isSuccess)
            assertEquals(1_000_000_000_000L, r.getOrNull()?.maxBalance)
            assertEquals(3000L, r.getOrNull()?.joinReadyDelayMillis)
        }
    }

    @Test
    @DisplayName("deposit が balance/version を返す")
    fun deposit() = runBlocking {
        val client = HttpClientFactory.create(config(), json("{\"balance\":500,\"version\":1}"))
        client.use {
            val req = VaultDepositRequest(uuid.toString(), 500, "test", "n", "表示", "srv", "op-1", "MAN10_API")
            val r = VaultApiClient(it).deposit(req)
            assertTrue(r.isSuccess)
            assertEquals(500L, r.getOrNull()?.balance)
        }
    }

    @Test
    @DisplayName("transfer が from/to の残高を返す")
    fun transfer() = runBlocking {
        val body = "{\"fromBalance\":700,\"fromVersion\":2,\"toBalance\":300,\"toVersion\":1}"
        val client = HttpClientFactory.create(config(), json(body))
        client.use {
            val req = VaultTransferRequest(uuid.toString(), toUuid.toString(), 300, "test", "n", "表示", "srv", "op-2")
            val r = VaultApiClient(it).transfer(req)
            assertTrue(r.isSuccess)
            assertEquals(700L, r.getOrNull()?.fromBalance)
            assertEquals(300L, r.getOrNull()?.toBalance)
        }
    }

    @Test
    @DisplayName("move が vault/bank の残高を返す")
    fun move() = runBlocking {
        val body = "{\"vaultBalance\":400,\"vaultVersion\":3,\"bankBalance\":600}"
        val client = HttpClientFactory.create(config(), json(body))
        client.use {
            val req = VaultMoveRequest(uuid.toString(), 600, "VaultToBank", "test", "n", "表示", "srv", "op-3")
            val r = VaultApiClient(it).move(req)
            assertTrue(r.isSuccess)
            assertEquals(400L, r.getOrNull()?.vaultBalance)
            assertEquals(600L, r.getOrNull()?.bankBalance)
        }
    }

    @Test
    @DisplayName("withdraw の 409(InsufficientFunds) が Result.failure になり code を取得できる")
    fun withdrawInsufficient() = runBlocking {
        val body = "{\"title\":\"残高が不足しています\",\"status\":409,\"code\":\"InsufficientFunds\"}"
        val client = HttpClientFactory.create(config(), json(body, HttpStatusCode.Conflict))
        client.use {
            val req = VaultWithdrawRequest(uuid.toString(), 100, "test", "n", "表示", "srv", "op-4", "MAN10_API")
            val r = VaultApiClient(it).withdraw(req)
            assertTrue(r.isFailure)
            val ex = r.exceptionOrNull()
            assertTrue(ex is ApiHttpException)
            assertEquals("InsufficientFunds", (ex as ApiHttpException).code)
        }
    }
}
