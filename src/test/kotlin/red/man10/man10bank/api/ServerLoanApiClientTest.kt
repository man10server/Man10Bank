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
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.UUID

/**
 * ServerLoanApiClient のテスト（Ktor MockEngine）。
 * - 延滞状態の照会が GET /api/ServerLoan/{uuid}/overdue を呼び、overdue をデコードすること。
 * - 契約の共有（リンク）の各操作が、決められたパスとクエリで呼ばれ、契約の状態をデコードすること。
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

    private val other = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val contractJson = """
        {"ownerUuid":"$uuid","ownerPlayer":"owner",
         "members":[{"uuid":"$other","player":"member","linkedBy":"admin","requestedAt":"2026-11-27T00:00:00","approvedAt":"2026-11-27T00:00:00"}],
         "pendingRequests":[],
         "outgoingRequest":null}
    """.trimIndent()

    /** リンク系の API を1回呼び、HTTP メソッド・パス・クエリと、デコード結果を返す。 */
    private fun callLinkApi(
        call: suspend ServerLoanApiClient.() -> Result<ServerLoanContract>,
    ): Triple<HttpMethod?, String, Map<String, String>> = runBlocking {
        var method: HttpMethod? = null
        var path = ""
        var query = emptyMap<String, String>()
        val engine = MockEngine { request ->
            method = request.method
            path = request.url.encodedPath
            query = request.url.parameters.names().associateWith { request.url.parameters[it].orEmpty() }
            respond(contractJson, HttpStatusCode.OK, json)
        }
        HttpClientFactory.create(config(), engine).use {
            val contract = ServerLoanApiClient(it).call().getOrThrow()
            assertEquals(uuid.toString(), contract.ownerUuid)
            assertEquals("owner", contract.ownerPlayer)
            assertEquals(listOf("member" to "admin"), contract.members.map { m -> m.player to m.linkedBy })
            assertEquals(null, contract.outgoingRequest)
        }
        Triple(method, path, query)
    }

    @Test
    @DisplayName("contractがGET /api/ServerLoan/{uuid}/contractを呼び、契約の状態を返す")
    fun contractReadsContract() {
        assertEquals(
            Triple(HttpMethod.Get, "/api/ServerLoan/$uuid/contract", emptyMap<String, String>()),
            callLinkApi { contract(uuid) },
        )
    }

    @Test
    @DisplayName("申請・承認・拒否・取り消し・脱退が、対応するパスとクエリでPOSTされる")
    fun linkOperationsPostToExpectedPaths() {
        val base = "/api/ServerLoan/$uuid/link"
        assertEquals(Triple(HttpMethod.Post, "$base/request", mapOf("target" to other.toString())), callLinkApi { requestLink(uuid, other) })
        assertEquals(Triple(HttpMethod.Post, "$base/approve", mapOf("member" to other.toString())), callLinkApi { approveLink(uuid, other) })
        assertEquals(Triple(HttpMethod.Post, "$base/reject", mapOf("member" to other.toString())), callLinkApi { rejectLink(uuid, other) })
        assertEquals(Triple(HttpMethod.Post, "$base/cancel", emptyMap<String, String>()), callLinkApi { cancelLinkRequest(uuid) })
        assertEquals(Triple(HttpMethod.Post, "$base/leave", emptyMap<String, String>()), callLinkApi { leaveLink(uuid) })
    }

    @Test
    @DisplayName("運営の強制リンク・解除が、操作者と枠の初期化の指定を付けてPOSTされる")
    fun forceLinkOperationsSendOperator() {
        val base = "/api/ServerLoan/$uuid/link"
        assertEquals(
            Triple(HttpMethod.Post, "$base/force", mapOf("owner" to other.toString(), "operator" to "admin", "resetLimit" to "true")),
            callLinkApi { forceLink(uuid, other, "admin", resetLimit = true) },
        )
        assertEquals(
            Triple(HttpMethod.Post, "$base/remove", mapOf("operator" to "admin")),
            callLinkApi { forceUnlink(uuid, "admin") },
        )
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
