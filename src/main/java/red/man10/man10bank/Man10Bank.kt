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
        // 設定をロード
        configManager = ConfigManager(this)
        val apiConfig = try {
            configManager.load()
        } catch (e: Exception) {
            logger.severe("config.yml の読み込みに失敗しました: ${e.message}")
            // API設定なしでは動作できないため、無効化
            server.pluginManager.disablePlugin(this)
            return
        }

        // HttpClient と CoroutineScope を初期化
        httpClient = HttpClientFactory.create(apiConfig)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // サービス初期化
        healthService = HealthService(HealthApiClient(httpClient))

        // 非同期でヘルスチェックを実行
        scope.launch {
            val result = healthService.getHealth()
            result.onSuccess { h ->
                logger.info(
                    "ヘルスチェック成功: service=${h.service}, db=${h.database}, uptime=${h.uptimeSeconds}s"
                )
            }.onFailure { e ->
                logger.warning("ヘルスチェック失敗: ${e.message}")
            }
        }
    }

    override fun onDisable() {
        // スコープとクライアントをクリーンアップ
        if (this::scope.isInitialized) scope.cancel()
        if (this::httpClient.isInitialized) httpClient.close()
    }
}
