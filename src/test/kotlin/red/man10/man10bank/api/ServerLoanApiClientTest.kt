package red.man10.man10bank.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.UUID

/**
 * ServerLoanApiClient のテスト（Ktor MockEngine）。
 * - 延滞状態の照会が GET /api/ServerLoan/{uuid}/overdue を呼び、overdue をデコードすること。
 */
@DisplayName("ServerLoanApiClient のテスト")
class ServerLoanApiClientTest {

    private val uuid = UUID.fromString("9c4161a9-0f5f-4317-835c-0bb196a7defa")
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun config(): ApiConfig =
        ApiConfig(
            baseUrl = "http://localhost",
            apiKey = null,
            timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
            retries = 0,
        )

    @Test
    @DisplayName("isOverdueがGET /api/ServerLoan/{uuid}/overdueを呼び、overdueを返す")
    fun isOverdueReadsFlag() = runBlocking {
        var method: HttpMethod? = null
        var path = ""
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            respond("{\"overdue\":true}", HttpStatusCode.OK, json)
        }
        HttpClientFactory.create(config(), engine).use {
            val result = ServerLoanApiClient(it).isOverdue(uuid)
            assertTrue(result.isSuccess)
            assertEquals(true, result.getOrNull())
        }
        assertEquals(HttpMethod.Get, method)
        assertEquals("/api/ServerLoan/$uuid/overdue", path)
    }

    @Test
    @DisplayName("isOverdueはサーバーエラー時に失敗を返す")
    fun isOverdueFailsOnServerError() = runBlocking {
        val engine = MockEngine { respond("{\"title\":\"error\"}", HttpStatusCode.InternalServerError, json) }
        HttpClientFactory.create(config(), engine).use {
            val result = ServerLoanApiClient(it).isOverdue(uuid)
            assertTrue(result.isFailure)
        }
    }
}
