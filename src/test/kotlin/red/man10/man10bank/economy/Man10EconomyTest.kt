package red.man10.man10bank.economy

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
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import red.man10.man10bank.service.vault.VaultCache
import red.man10.man10bank.service.vault.VaultService

@DisplayName("Man10Economy のテスト（MockBukkit）")
class Man10EconomyTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var cache: VaultCache
    private lateinit var service: VaultService
    private lateinit var economy: Man10Economy
    private lateinit var client: HttpClient

    private fun config() = ApiConfig(
        baseUrl = "http://localhost",
        apiKey = null,
        timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
        retries = 0,
    )

    // write-through/ensure 用に常に正常な残高を返すエンジン（結果は assert 対象外）。
    private fun engine() = MockEngine {
        respond(
            content = """{"balance":0,"version":0}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        cache = VaultCache()
        client = HttpClientFactory.create(config(), engine())
        service = VaultService(plugin, "test", CoroutineScope(Dispatchers.Unconfined), VaultApiClient(client), cache)
        service.setConnected(true)
        economy = Man10Economy(plugin, service, "円", "円")
    }

    @AfterEach
    fun teardown() {
        client.close()
        MockBukkit.unmock()
    }

    @Test
    @DisplayName("基本メタ情報: name/単一通貨/小数桁0/書式")
    fun meta() {
        assertEquals("Man10Bank", economy.name)
        assertFalse(economy.hasBankSupport())
        assertEquals(0, economy.fractionalDigits())
        assertEquals("1,234円", economy.format(1234.0))
        assertTrue(economy.isEnabled)
        service.setConnected(false)
        assertFalse(economy.isEnabled)
    }

    @Test
    @DisplayName("getBalance/has はキャッシュから同期取得する")
    fun balanceAndHas() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 500.0, 1)
        assertEquals(500.0, economy.getBalance(p))
        assertTrue(economy.has(p, 300.0))
        assertTrue(economy.has(p, 500.0))
        assertFalse(economy.has(p, 501.0))
    }

    @Test
    @DisplayName("depositPlayer: キャッシュ済みは楽観加算して SUCCESS（新残高を返す）")
    fun depositCached() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        val res = economy.depositPlayer(p, 100.0)
        assertTrue(res.transactionSuccess())
        assertEquals(1100.0, res.balance)
    }

    @Test
    @DisplayName("withdrawPlayer: 十分なら SUCCESS、不足なら FAILURE")
    fun withdrawCached() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        val ok = economy.withdrawPlayer(p, 400.0)
        assertTrue(ok.transactionSuccess())
        assertEquals(600.0, ok.balance)

        cache.preload(p.uniqueId, 100.0, 2)
        val ng = economy.withdrawPlayer(p, 500.0)
        assertFalse(ng.transactionSuccess())
    }

    @Test
    @DisplayName("fail-closed: 未接続なら（キャッシュ済みでも）deposit/withdraw とも FAILURE・残高不変")
    fun failClosedWhenDisconnected() {
        val p = server.addPlayer()
        cache.preload(p.uniqueId, 1000.0, 1)
        service.setConnected(false)
        assertFalse(economy.depositPlayer(p, 100.0).transactionSuccess())
        assertFalse(economy.withdrawPlayer(p, 100.0).transactionSuccess())
        assertEquals(1000.0, economy.getBalance(p), "未接続でも読みは継続するが書きは反映されない")
    }

    @Test
    @DisplayName("未キャッシュ（オフライン/他サーバー在席）は deposit/withdraw とも FAILURE")
    fun offlineRejected() {
        val p = server.addPlayer() // キャッシュには載せない
        assertFalse(economy.depositPlayer(p, 100.0).transactionSuccess())
        assertFalse(economy.withdrawPlayer(p, 100.0).transactionSuccess())
        // 未キャッシュの getBalance はベストエフォートで 0
        assertEquals(0.0, economy.getBalance(server.addPlayer()))
    }

    @Test
    @DisplayName("preload は未キャッシュ（不在）エントリを初期投入する（loadIntoCache 自己修復の土台）")
    fun preloadPopulatesAbsentEntry() = runBlocking {
        val uuid = java.util.UUID.randomUUID()
        assertFalse(cache.contains(uuid))
        service.preload(uuid) // engine() は balance/version を返す
        assertTrue(cache.contains(uuid))
    }

    @Test
    @DisplayName("bank 系はすべて NOT_IMPLEMENTED")
    fun bankNotImplemented() {
        val res = economy.createBank("x", null as org.bukkit.OfflinePlayer?)
        assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, res.type)
        assertTrue(economy.banks.isEmpty())
    }
}
