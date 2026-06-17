package red.man10.man10bank.service.vault

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.api.model.request.VaultMoveDirection
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import java.util.Collections
import java.util.UUID

/**
 * VaultService の整合性方針（真実優先 / fail-closed・VaultProvider 4.6）のテスト。
 * - 未接続(connected=false)での書き込み拒否（楽観経路 deposit/withdraw、確定経路 *Confirmed）。
 * - 確定経路（depositConfirmed/withdrawConfirmed/move）の成功時 reconcile・失敗時非適用。
 */
@DisplayName("VaultService の整合性方針テスト（MockBukkit + Ktor MockEngine）")
class VaultServiceTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var cache: VaultCache
    private val clients = mutableListOf<HttpClient>()

    /** すべてのリクエストの path を記録する（ダウン時に POST を一切打たないことの検証用）。 */
    private val requestPaths = Collections.synchronizedList(mutableListOf<String>())

    private fun config() = ApiConfig(
        baseUrl = "http://localhost",
        apiKey = null,
        timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
        retries = 0,
    )

    private fun newService(connected: Boolean, body: String, status: HttpStatusCode = HttpStatusCode.OK): VaultService {
        val engine = MockEngine { req ->
            requestPaths.add(req.url.encodedPath)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClientFactory.create(config(), engine)
        clients.add(client)
        val service = VaultService(plugin, "test", CoroutineScope(Dispatchers.Unconfined), VaultApiClient(client), cache)
        service.setConnected(connected)
        return service
    }

    /** エンジンが例外を投げる（接続レベル障害を模す）サービスを作る。 */
    private fun newThrowingService(connected: Boolean, error: Throwable): VaultService {
        val engine = MockEngine { throw error }
        val client = HttpClientFactory.create(config(), engine)
        clients.add(client)
        val service = VaultService(plugin, "test", CoroutineScope(Dispatchers.Unconfined), VaultApiClient(client), cache)
        service.setConnected(connected)
        return service
    }

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        cache = VaultCache()
        requestPaths.clear()
    }

    @AfterEach
    fun teardown() {
        clients.forEach { it.close() }
        clients.clear()
        MockBukkit.unmock()
    }

    @Test
    @DisplayName("fail-closed: 未接続なら deposit は即 FAILURE・キャッシュ不変・POST を打たない")
    fun depositFailClosedWhenDisconnected() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        val service = newService(connected = false, body = """{"balance":0,"version":0}""")

        val res = service.deposit(p, 100.0)

        assertFalse(res.transactionSuccess())
        assertEquals(1000.0, cache.get(p.uniqueId), "キャッシュは楽観加算されない")
        assertTrue(requestPaths.isEmpty(), "未接続では write-through を投入しない")
    }

    @Test
    @DisplayName("fail-closed: 未接続なら withdraw は即 FAILURE・キャッシュ不変・POST を打たない")
    fun withdrawFailClosedWhenDisconnected() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        val service = newService(connected = false, body = """{"balance":0,"version":0}""")

        val res = service.withdraw(p, 100.0)

        assertFalse(res.transactionSuccess())
        assertEquals(1000.0, cache.get(p.uniqueId))
        assertTrue(requestPaths.isEmpty())
    }

    @Test
    @DisplayName("接続中は従来どおり楽観加算して SUCCESS を返す（新残高）")
    fun depositOptimisticWhenConnected() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        val service = newService(connected = true, body = """{"balance":1100,"version":2}""")

        val res = service.deposit(p, 100.0)

        assertTrue(res.transactionSuccess())
        assertEquals(1100.0, res.balance)
    }

    @Test
    @DisplayName("depositConfirmed: 成功で確定残高+versionにキャッシュ補正")
    fun depositConfirmedReconciles() = runBlocking {
        val uuid = UUID.randomUUID()
        cache.preload(uuid, 500.0, 1)
        val service = newService(connected = true, body = """{"balance":700,"version":5}""")

        val r = service.depositConfirmed(uuid, 200.0, "n", "d")

        assertTrue(r.isSuccess)
        assertEquals(700.0, cache.get(uuid))
        assertEquals(5L, cache.getVersion(uuid))
    }

    @Test
    @DisplayName("depositConfirmed: 未接続は Result.failure で POST を打たない")
    fun depositConfirmedFailClosed() = runBlocking {
        val uuid = UUID.randomUUID()
        val service = newService(connected = false, body = """{"balance":0,"version":0}""")

        val r = service.depositConfirmed(uuid, 200.0, "n", "d")

        assertTrue(r.isFailure)
        assertTrue(requestPaths.isEmpty())
    }

    @Test
    @DisplayName("withdrawConfirmed: 409(InsufficientFunds)は失敗・キャッシュ不変（reconcileしない）")
    fun withdrawConfirmedInsufficient() = runBlocking {
        val uuid = UUID.randomUUID()
        cache.preload(uuid, 100.0, 3)
        val body = """{"title":"残高が不足しています","status":409,"code":"InsufficientFunds"}"""
        val service = newService(connected = true, body = body, status = HttpStatusCode.Conflict)

        val r = service.withdrawConfirmed(uuid, 200.0, "n", "d")

        assertTrue(r.isFailure)
        assertEquals(100.0, cache.get(uuid), "失敗時は確定値で上書きしない")
        assertEquals(3L, cache.getVersion(uuid))
    }

    @Test
    @DisplayName("fail-closed: 未接続なら move は Result.failure で POST を打たない")
    fun moveFailClosedWhenDisconnected() = runBlocking {
        val uuid = UUID.randomUUID()
        cache.preload(uuid, 1000.0, 1)
        val service = newService(connected = false, body = """{"vaultBalance":0,"bankBalance":0,"vaultVersion":0}""")

        val r = service.move(uuid, 400.0, VaultMoveDirection.VaultToBank, "n", "d")

        assertTrue(r.isFailure)
        assertEquals(1000.0, cache.get(uuid))
        assertTrue(requestPaths.isEmpty())
    }

    @Test
    @DisplayName("fail-closed: 未接続なら transfer は Result.failure で POST を打たない")
    fun transferFailClosedWhenDisconnected() = runBlocking {
        val from = UUID.randomUUID()
        val to = UUID.randomUUID()
        val service = newService(connected = false, body = """{"balance":0,"version":0}""")

        val r = service.transfer(from, to, 100.0, "n", "d")

        assertTrue(r.isFailure)
        assertTrue(requestPaths.isEmpty())
    }

    @Test
    @DisplayName("接続レベル障害: 即 fail-closed（connected=false）にし、WS 再接続を促す")
    fun transportFailureFlipsConnectedAndRequestsReconnect() = runBlocking {
        var reconnectRequested = false
        val service = newThrowingService(connected = true, error = java.net.ConnectException("Connection refused"))
        service.setReconnectRequester { reconnectRequested = true }
        assertTrue(service.isReady(), "前提: 接続中")

        val r = service.move(UUID.randomUUID(), 100.0, VaultMoveDirection.VaultToBank, "n", "d")

        assertTrue(r.isFailure)
        assertFalse(service.isReady(), "接続レベル障害で connected=false に倒す")
        assertTrue(reconnectRequested, "WS 再接続を促す")
    }

    @Test
    @DisplayName("HTTPエラー(409=サービスは応答)では fail-closed にしない（固着回避）")
    fun httpErrorDoesNotFlipConnected() = runBlocking {
        var reconnectRequested = false
        val body = """{"title":"残高が不足しています","status":409,"code":"InsufficientFunds"}"""
        val service = newService(connected = true, body = body, status = HttpStatusCode.Conflict)
        service.setReconnectRequester { reconnectRequested = true }

        val r = service.withdrawConfirmed(UUID.randomUUID(), 100.0, "n", "d")

        assertTrue(r.isFailure)
        assertTrue(service.isReady(), "4xx/5xx は接続生存とみなし connected を維持する")
        assertFalse(reconnectRequested)
    }

    @Test
    @DisplayName("write-through失敗: 在席プレイヤーへエラーID付きで通知する（誰がいくらの取引で失敗したか追跡可能）")
    fun writeThroughFailureNotifiesOnlinePlayer() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        // 接続中だが REST 書き込みが接続レベル障害で失敗する状況を模す。
        val service = newThrowingService(connected = true, error = java.net.ConnectException("Connection refused"))

        val res = service.deposit(p, 100.0)

        // 楽観経路なので即 SUCCESS だが、非同期 write-through は失敗する。
        assertTrue(res.transactionSuccess(), "楽観経路は即 SUCCESS を返す")
        // write-through は scope(IO) で非同期に走り、プレイヤー通知はメインスレッドへディスパッチされる。
        // MockBukkit はティックでスケジュール済みタスクを実行するため、到達するまでティックを進めて待つ。
        var message: String? = null
        var tries = 0
        while (message == null && tries < 50) {
            server.scheduler.performTicks(1)
            message = p.nextMessage()
            if (message == null) Thread.sleep(10)
            tries++
        }
        assertTrue(message != null && message.contains("エラーID"), "プレイヤーへエラーIDを通知する: $message")
        assertTrue(message!!.contains("Man10Bank"), "Man10Bank 上でエラーが起きた旨を通知する")
    }

    @Test
    @DisplayName("move: 成功で電子マネー側キャッシュを確定値に補正し、叩く先は /api/Vault/move のみ（補償の二重書き込みが無い）")
    fun moveReconcilesAndHitsSingleEndpoint() = runBlocking {
        val uuid = UUID.randomUUID()
        cache.preload(uuid, 1000.0, 1)
        val service = newService(connected = true, body = """{"vaultBalance":600,"bankBalance":400,"vaultVersion":3}""")

        val r = service.move(uuid, 400.0, VaultMoveDirection.VaultToBank, "n", "d")

        assertTrue(r.isSuccess)
        assertEquals(600.0, cache.get(uuid))
        assertEquals(3L, cache.getVersion(uuid))
        assertEquals(1, requestPaths.size, "単一トランザクション：補償用の追加 POST を打たない")
        assertTrue(requestPaths.single().endsWith("/api/Vault/move"))
    }
}
