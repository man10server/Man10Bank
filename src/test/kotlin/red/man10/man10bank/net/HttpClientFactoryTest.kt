package red.man10.man10bank.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import java.util.concurrent.atomic.AtomicInteger

/**
 * HttpClientFactory のテスト（Ktor MockEngine）。
 * - リトライがメソッド種別（冪等のみ）で正しく分岐すること。
 * - 非2xx応答が ApiHttpException へ正規化され ProblemDetails/生本文を取得できること。
 * - apiKey 設定有無で Bearer ヘッダの付与/非付与が切り替わること。
 */
@DisplayName("HttpClientFactory のテスト")
class HttpClientFactoryTest {

    // テスト用の基本設定。リトライ回数は2回（最大3アクセス）。
    // requestMs はリトライ間の指数バックオフ待機を含めても余裕がある値にし、
    // タイムアウトではなく ApiHttpException でリトライ完了を観測できるようにする。
    private fun config(apiKey: String? = null, retries: Int = 2): ApiConfig =
        ApiConfig(
            baseUrl = "http://localhost",
            apiKey = apiKey,
            timeouts = ApiTimeouts(requestMs = 60_000, connectMs = 5_000, socketMs = 60_000),
            retries = retries,
        )

    @Test
    @DisplayName("POSTが5xx応答でもリトライされない（リクエスト回数=1）")
    fun postDoesNotRetryOnServerError() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            respondError(HttpStatusCode.InternalServerError)
        }
        val client = HttpClientFactory.create(config(retries = 2), engine)
        client.use {
            // POST は非冪等のため一切リトライしない。1回で例外になる。
            assertThrows(ApiHttpException::class.java) {
                runBlocking { it.post("/api/Bank/deposit").bodyAsText() }
            }
        }
        assertEquals(1, count.get(), "POSTは5xxでもリトライされてはならない")
    }

    @Test
    @DisplayName("GETが5xx応答で設定回数までリトライされる（最大3アクセス）")
    fun getRetriesOnServerError() = runBlocking {
        val count = AtomicInteger(0)
        val engine = MockEngine {
            count.incrementAndGet()
            respondError(HttpStatusCode.InternalServerError)
        }
        val client = HttpClientFactory.create(config(retries = 2), engine)
        client.use {
            assertThrows(ApiHttpException::class.java) {
                runBlocking { it.get("/api/Bank/x/balance").bodyAsText() }
            }
        }
        // 初回1回 + リトライ2回 = 3アクセス。
        assertEquals(3, count.get(), "GETは5xxで設定回数までリトライされる")
    }

    @Test
    @DisplayName("4xx応答がApiHttpExceptionへ正規化されProblemDetailsのcode/titleを取得できる")
    fun normalizesProblemDetailsOn4xx() = runBlocking {
        // ASP.NET Core 風の ProblemDetails（extensions.code はトップレベルへ展開される）。
        val body = """
            {"title":"残高が不足しています","status":409,"code":"InsufficientFunds"}
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClientFactory.create(config(), engine)
        val ex = client.use {
            assertThrows(ApiHttpException::class.java) {
                runBlocking { it.post("/api/Bank/transfer").bodyAsText() }
            }
        }
        assertEquals(HttpStatusCode.Conflict, ex.status)
        assertNotNull(ex.problem, "ProblemDetailsがパースされること")
        assertEquals("InsufficientFunds", ex.code, "extensions.code を取得できること")
        assertEquals("残高が不足しています", ex.message, "messageはProblemDetails.titleになる")
    }

    @Test
    @DisplayName("ProblemDetailsでない生ボディでもmessageに本文が残る")
    fun keepsRawBodyWhenNotProblemDetails() = runBlocking {
        // JSONとしてパースできないプレーンテキスト本文。
        val raw = "Internal Server Error: 致命的なエラーが発生しました"
        val engine = MockEngine {
            respond(
                content = raw,
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }
        // リトライで複数回呼ばれないよう retries=0 にする。
        val client = HttpClientFactory.create(config(retries = 0), engine)
        val ex = client.use {
            assertThrows(ApiHttpException::class.java) {
                runBlocking { it.get("/api/Bank/x/balance").bodyAsText() }
            }
        }
        assertNull(ex.problem, "ProblemDetailsとしてはパースできない")
        assertNull(ex.code, "codeは取得できない")
        assertTrue(ex.message!!.contains("致命的なエラーが発生しました"), "生本文がmessageへ残る")
        assertTrue(ex.rawBody!!.contains("致命的なエラーが発生しました"), "生本文がrawBodyへ残る")
    }

    @Test
    @DisplayName("apiKey設定時はAuthorization: Bearerヘッダが付与される")
    fun addsBearerHeaderWhenApiKeySet() = runBlocking {
        var authHeader: String? = null
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            respond("{\"balance\":0.0}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClientFactory.create(config(apiKey = "secret-token"), engine)
        client.use { it.get("/api/Bank/x/balance").bodyAsText() }
        assertEquals("Bearer secret-token", authHeader, "apiKey設定時はBearerが付与される")
    }

    @Test
    @DisplayName("apiKey未設定時はAuthorizationヘッダが付与されない")
    fun omitsBearerHeaderWhenApiKeyAbsent() = runBlocking {
        var hasAuthHeader = true
        val engine = MockEngine { request ->
            hasAuthHeader = request.headers.contains(HttpHeaders.Authorization)
            respond("{\"balance\":0.0}", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = HttpClientFactory.create(config(apiKey = null), engine)
        client.use { it.get("/api/Bank/x/balance").bodyAsText() }
        assertEquals(false, hasAuthHeader, "apiKey未設定時はAuthorizationヘッダを付与しない")
    }
}
