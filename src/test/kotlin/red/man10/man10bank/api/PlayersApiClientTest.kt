package red.man10.man10bank.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.model.request.PlayerRegisterRequest
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory

/**
 * PlayersApiClient のテスト（Ktor MockEngine）。
 * - 名簿登録が POST /api/Players に uuid/name を送り、プロフィールをデコードすること。
 * - 名前解決が by-name パスを使い、未登録は ApiHttpException(404, PlayerNotFound) になること。
 */
@DisplayName("PlayersApiClient のテスト")
class PlayersApiClientTest {

    private val uuid = "9c4161a9-0f5f-4317-835c-0bb196a7defa"
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun config(): ApiConfig =
        ApiConfig(
            baseUrl = "http://localhost",
            apiKey = null,
            timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
            retries = 0,
        )

    @Test
    @DisplayName("registerがPOST /api/Playersにuuidとnameを送り、プロフィールを返す")
    fun registerSendsUuidAndName() = runBlocking {
        var method: HttpMethod? = null
        var path = ""
        var body = ""
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond("{\"uuid\":\"$uuid\",\"name\":\"Steve\"}", HttpStatusCode.OK, json)
        }
        HttpClientFactory.create(config(), engine).use {
            val result = PlayersApiClient(it).register(PlayerRegisterRequest(uuid, "Steve"))
            assertTrue(result.isSuccess)
            assertEquals("Steve", result.getOrNull()?.name)
            assertEquals(uuid, result.getOrNull()?.uuid)
        }
        assertEquals(HttpMethod.Post, method)
        assertEquals("/api/Players", path)
        assertTrue(body.contains("\"uuid\":\"$uuid\"") && body.contains("\"name\":\"Steve\""), "本文: $body")
    }

    @Test
    @DisplayName("findByNameがby-nameパスで問い合わせ、プロフィールを返す")
    fun findByNameUsesByNamePath() = runBlocking {
        var path = ""
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond("{\"uuid\":\"$uuid\",\"name\":\"Steve\"}", HttpStatusCode.OK, json)
        }
        HttpClientFactory.create(config(), engine).use {
            val result = PlayersApiClient(it).findByName("Steve")
            assertTrue(result.isSuccess)
            assertEquals(uuid, result.getOrNull()?.uuid)
        }
        assertEquals("/api/Players/by-name/Steve", path)
    }

    @Test
    @DisplayName("未登録の名前は404(PlayerNotFound)がResult.failureとして返る")
    fun findByNameUnknownBecomesPlayerNotFound() = runBlocking {
        val engine = MockEngine {
            respond(
                "{\"title\":\"プレイヤーが見つかりません。\",\"status\":404,\"code\":\"PlayerNotFound\"}",
                HttpStatusCode.NotFound,
                json,
            )
        }
        HttpClientFactory.create(config(), engine).use {
            val result = PlayersApiClient(it).findByName("nobody")
            assertTrue(result.isFailure)
            val ex = result.exceptionOrNull()
            assertTrue(ex is ApiHttpException)
            assertEquals("PlayerNotFound", (ex as ApiHttpException).code)
        }
    }
}
