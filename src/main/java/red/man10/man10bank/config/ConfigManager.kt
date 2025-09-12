package red.man10.man10bank.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * プラグイン設定の読み取りを担当するクラス。
 * - config.yml から WebAPI の接続情報等を読み込みます。
 */
class ConfigManager(private val plugin: JavaPlugin) {

    /** WebAPI のタイムアウト設定 */
    data class ApiTimeouts(
        val requestMs: Long = 10_000,
        val connectMs: Long = 3_000,
        val socketMs: Long = 10_000,
    )

    /** WebAPI の基本設定 */
    data class ApiConfig(
        val baseUrl: String,
        val apiKey: String?,
        val timeouts: ApiTimeouts = ApiTimeouts(),
        val retries: Int = 2,
    )

    /** 設定を読み込み、必要ならデフォルトを保存します。 */
    fun load(): ApiConfig {
        // config.yml が無い場合は生成
        plugin.saveDefaultConfig()
        val conf = plugin.config
        return readApiConfig(conf)
    }

    /** 設定を再読み込みします。 */
    fun reload(): ApiConfig {
        plugin.reloadConfig()
        return readApiConfig(plugin.config)
    }

    private fun readApiConfig(conf: FileConfiguration): ApiConfig {
        val section = conf.getConfigurationSection("api")
        val baseUrl = section?.getString("baseUrl")?.trim().orEmpty()
        val apiKey = section?.getString("apiKey")?.trim().orEmpty().ifEmpty { null }

        val timeouts = section?.getConfigurationSection("timeout")
        val requestMs = timeouts?.getLong("requestMs", 10_000) ?: 10_000
        val connectMs = timeouts?.getLong("connectMs", 3_000) ?: 3_000
        val socketMs = timeouts?.getLong("socketMs", 10_000) ?: 10_000

        val retries = section?.getInt("retries", 2) ?: 2

        require(baseUrl.isNotBlank()) { "config.yml の api.baseUrl が未設定です" }

        return ApiConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            timeouts = ApiTimeouts(
                requestMs = requestMs,
                connectMs = connectMs,
                socketMs = socketMs,
            ),
            retries = retries,
        )
    }
}

