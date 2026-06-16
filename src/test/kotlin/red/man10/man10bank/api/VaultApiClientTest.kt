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
import red.man10.man10bank.api.model.request.VaultMoveDirection
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultSetRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.UUID

@DisplayName("VaultApiClient のテスト（Ktor MockEngine）")
class VaultApiClientTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val uuid2 = "00000000-0000-0000-0000-000000000002"

    private fun config() = ApiConfig(
        baseUrl = "http://localhost",
        apiKey = null,
        timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
        retries = 0,
    )

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine {
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    @Test
    @DisplayName("getBalance が balance/version をデコードする")
    fun getBalance() = runBlocking {
        val client = HttpClientFactory.create(config(), jsonEngine("""{"balance":1500,"version":7}"""))
        client.use {
            val res = VaultApiClient(it).getBalance(uuid)
            assertTrue(res.isSuccess)
            assertEquals(1500.0, res.getOrNull()?.balance)
            assertEquals(7L, res.getOrNull()?.version)
        }
    }

    @Test
    @DisplayName("deposit/withdraw が確定残高+versionを返す")
    fun depositWithdraw() = runBlocking {
        HttpClientFactory.create(config(), jsonEngine("""{"balance":600,"version":3}""")).use {
            val api = VaultApiClient(it)
            val dep = api.deposit(VaultDepositRequest(uuid.toString(), 100.0, "t", "n", "d", "s"))
            assertEquals(600.0, dep.getOrNull()?.balance)
            assertEquals(3L, dep.getOrNull()?.version)
            val wd = api.withdraw(VaultWithdrawRequest(uuid.toString(), 100.0, "t", "n", "d", "s"))
            assertEquals(600.0, wd.getOrNull()?.balance)
        }
    }

    @Test
    @DisplayName("set が確定残高+versionを返す")
    fun set() = runBlocking {
        HttpClientFactory.create(config(), jsonEngine("""{"balance":1000,"version":9}""")).use {
            val res = VaultApiClient(it).set(VaultSetRequest(uuid.toString(), 1000.0, "admin", "n", "d", "s"))
            assertEquals(1000.0, res.getOrNull()?.balance)
            assertEquals(9L, res.getOrNull()?.version)
        }
    }

    @Test
    @DisplayName("move が両残高(vaultBalance/bankBalance/vaultVersion)を返す")
    fun move() = runBlocking {
        HttpClientFactory.create(config(), jsonEngine("""{"vaultBalance":600,"bankBalance":400,"vaultVersion":4}""")).use {
            val res = VaultApiClient(it).move(
                VaultMoveRequest(uuid.toString(), 400.0, VaultMoveDirection.VaultToBank, "t", "n", "d", "s")
            )
            assertTrue(res.isSuccess)
            assertEquals(600.0, res.getOrNull()?.vaultBalance)
            assertEquals(400.0, res.getOrNull()?.bankBalance)
            assertEquals(4L, res.getOrNull()?.vaultVersion)
        }
    }

    @Test
    @DisplayName("transfer の 409(InsufficientFunds) が Result.failure になり code を取得できる")
    fun transferInsufficient() = runBlocking {
        val body = """{"title":"残高が不足しています","status":409,"code":"InsufficientFunds"}"""
        HttpClientFactory.create(config(), jsonEngine(body, HttpStatusCode.Conflict)).use {
            val res = VaultApiClient(it).transfer(
                VaultTransferRequest(uuid.toString(), uuid2, 100.0, "t", "n", "d", "s")
            )
            assertTrue(res.isFailure)
            val ex = res.exceptionOrNull()
            assertTrue(ex is ApiHttpException)
            assertEquals("InsufficientFunds", (ex as ApiHttpException).code)
        }
    }
}
