package red.man10.man10bank

import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.config.ConfigManager
import red.man10.man10bank.net.HttpClientFactory
import red.man10.man10bank.service.HealthService

class Man10Bank : JavaPlugin(), Listener {

    // 設定/HTTPクライアント/スコープ
    private lateinit var configManager: ConfigManager
    private lateinit var httpClient: HttpClient
    private lateinit var scope: CoroutineScope

    // サービス
    private lateinit var healthService: HealthService

    override fun onEnable() {
        // 初期化フロー
        configManager = ConfigManager(this)
        val apiConfig = loadApiConfigOrDisable() ?: return
        initRuntime(apiConfig)
        initServices()
        runStartupHealthCheck()
    }

    override fun onDisable() {
        // スコープとクライアントをクリーンアップ
        if (this::scope.isInitialized) scope.cancel()
        if (this::httpClient.isInitialized) httpClient.close()
    }

    // 初期化ヘルパー
    private fun loadApiConfigOrDisable(): ConfigManager.ApiConfig? {
        return try {
            configManager.load()
        } catch (e: Exception) {
            logger.severe("config.yml の読み込みに失敗しました: ${e.message}")
            server.pluginManager.disablePlugin(this)
            null
        }
    }

    private fun initRuntime(apiConfig: ConfigManager.ApiConfig) {
        httpClient = HttpClientFactory.create(apiConfig)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun initServices() {
        healthService = HealthService(HealthApiClient(httpClient))
    }

    private fun runStartupHealthCheck() {
        scope.launch {
            val result = healthService.getHealth()
            result.onSuccess { h ->
                val msg = """
                    ================== ヘルスチェック ==================
                    サービス名   : ${h.service}
                    DB接続       : ${if (h.database) "成功" else "失敗"}
                    稼働時間     : ${h.uptimeSeconds} 秒
                    サーバー時刻 : ${h.serverTimeUtc}
                    起動時刻     : ${h.startedAtUtc}
                    ====================================================
                """.trimIndent()
                logger.info(msg)
            }.onFailure { e ->
                logger.warning("ヘルスチェック失敗: ${e.message}")
            }
        }
    }
}
