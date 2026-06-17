package red.man10.man10bank.service

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
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.config.ConfigManager.ApiConfig
import red.man10.man10bank.config.ConfigManager.ApiTimeouts
import red.man10.man10bank.net.HttpClientFactory
import red.man10.man10bank.service.vault.VaultCache
import red.man10.man10bank.service.vault.VaultService
import java.util.Collections

/**
 * AtmService の fail-closed ゲートテスト（VaultProvider 4.6）。
 * 未接続中は現金も電子マネーも動かさず、サービスへ一切リクエストしないことを検証する。
 * （確定応答の正常系は [red.man10.man10bank.service.vault.VaultServiceTest] が depositConfirmed/withdrawConfirmed で担保。）
 */
@DisplayName("AtmService の fail-closed ゲートテスト（MockBukkit）")
class AtmServiceTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: JavaPlugin
    private lateinit var client: HttpClient
    private lateinit var vaultService: VaultService
    private lateinit var atmService: AtmService

    private val requestPaths = Collections.synchronizedList(mutableListOf<String>())

    private fun config() = ApiConfig(
        baseUrl = "http://localhost",
        apiKey = null,
        timeouts = ApiTimeouts(requestMs = 2_000, connectMs = 1_000, socketMs = 2_000),
        retries = 0,
    )

    @BeforeEach
    fun setup() {
        server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        requestPaths.clear()
        val engine = MockEngine { req ->
            requestPaths.add(req.url.encodedPath)
            respond(
                content = """{"balance":0,"version":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        client = HttpClientFactory.create(config(), engine)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        vaultService = VaultService(plugin, "test", scope, VaultApiClient(client), VaultCache())
        atmService = AtmService(plugin, scope, AtmApiClient(client), vaultService, CashItemManager(plugin))
    }

    @AfterEach
    fun teardown() {
        client.close()
        MockBukkit.unmock()
    }

    @Test
    @DisplayName("入金: 未接続なら現金アイテムを消費せず、サービスへリクエストしない")
    fun depositGateBlocksWhenDisconnected() {
        vaultService.setConnected(false)
        val p = server.addPlayer()
        val cash = ItemStack(Material.PAPER, 5)

        atmService.depositCashToVault(p, arrayOf(cash))

        assertEquals(5, cash.amount, "未接続では現金を確保(消費)しない")
        assertTrue(requestPaths.isEmpty(), "未接続では入金リクエストを打たない")
    }

    @Test
    @DisplayName("出金: 未接続ならサービスへリクエストしない（現金を付与しない）")
    fun withdrawGateBlocksWhenDisconnected() {
        vaultService.setConnected(false)
        val p = server.addPlayer()

        atmService.withdrawVaultToCash(p, 1000.0)

        assertTrue(requestPaths.isEmpty(), "未接続では出金リクエストを打たない")
    }
}
