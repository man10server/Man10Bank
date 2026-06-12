package red.man10.man10bank.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Logger

/**
 * プラグイン設定の読み取りを担当するクラス。
 * - config.yml から WebAPI の接続情報等を読み込みます。
 */
class ConfigManager(private val plugin: JavaPlugin) {

    companion object {
        // タイムアウト/リトライのデフォルト値は1箇所に集約する（二重定義によるバグを防ぐ）。
        /** リクエスト全体のタイムアウト既定値（ミリ秒）。 */
        const val DEFAULT_REQUEST_MS: Long = 10_000
        /** 接続確立のタイムアウト既定値（ミリ秒）。 */
        const val DEFAULT_CONNECT_MS: Long = 3_000
        /** ソケット読み書きのタイムアウト既定値（ミリ秒）。 */
        const val DEFAULT_SOCKET_MS: Long = 10_000
        /** 失敗時の自動リトライ回数の既定値。 */
        const val DEFAULT_RETRIES: Int = 2
    }

    /** WebAPI のタイムアウト設定 */
    data class ApiTimeouts(
        val requestMs: Long = DEFAULT_REQUEST_MS,
        val connectMs: Long = DEFAULT_CONNECT_MS,
        val socketMs: Long = DEFAULT_SOCKET_MS,
    )

    /** WebAPI の基本設定 */
    data class ApiConfig(
        val baseUrl: String,
        val apiKey: String?,
        val timeouts: ApiTimeouts = ApiTimeouts(),
        val retries: Int = DEFAULT_RETRIES,
    )

    /** 設定を読み込み、必要ならデフォルトを保存します。 */
    fun load(): ApiConfig {
        // config.yml が無い場合は生成
        plugin.saveDefaultConfig()
        val conf = plugin.config
        return readApiConfig(conf, plugin.logger)
    }

    /** 設定を再読み込みします。 */
    fun reload(): ApiConfig {
        plugin.reloadConfig()
        return readApiConfig(plugin.config, plugin.logger)
    }

    /**
     * 設定パース本体。plugin フィールドに依存せず Logger を引数で受け取ることで、
     * Bukkitサーバーに依存しない単体テストを可能にしている。
     */
    private fun readApiConfig(conf: FileConfiguration, logger: Logger): ApiConfig {
        val section = conf.getConfigurationSection("api")
        val baseUrl = section?.getString("baseUrl")?.trim().orEmpty()
        val apiKey = section?.getString("apiKey")?.trim().orEmpty().ifEmpty { null }

        val timeouts = section?.getConfigurationSection("timeout")
        val requestMs = timeouts?.getLong("requestMs", DEFAULT_REQUEST_MS) ?: DEFAULT_REQUEST_MS
        val connectMs = timeouts?.getLong("connectMs", DEFAULT_CONNECT_MS) ?: DEFAULT_CONNECT_MS
        val socketMs = timeouts?.getLong("socketMs", DEFAULT_SOCKET_MS) ?: DEFAULT_SOCKET_MS

        val retries = section?.getInt("retries", DEFAULT_RETRIES) ?: DEFAULT_RETRIES

        require(baseUrl.isNotBlank()) { "config.yml の api.baseUrl が未設定です" }

        warnIfInsecureConfig(baseUrl, apiKey, logger)

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

    /**
     * 設定上の安全性に関する起動時警告を出す（DESIGN 1.1）。
     * - apiKey 未設定: 認証ヘッダ無しで全リクエストが飛ぶため警告。
     * - baseUrl が http:// かつ localhost 以外: 平文通信の危険があるため警告。
     */
    private fun warnIfInsecureConfig(baseUrl: String, apiKey: String?, logger: Logger) {
        if (apiKey.isNullOrBlank()) {
            logger.warning(
                "config.yml の api.apiKey が未設定です。認証ヘッダ無しでWebAPIへ接続します。" +
                    "サーバー側が認証を要求する場合は apiKey を設定してください。"
            )
        }
        if (isInsecureRemoteHttp(baseUrl)) {
            logger.warning(
                "config.yml の api.baseUrl が http:// かつ localhost 以外です（$baseUrl）。" +
                    "通信が平文になります。本番環境では https:// の利用を推奨します。"
            )
        }
    }

    /** http:// かつ接続先が localhost/127.0.0.1/::1 以外であれば true。 */
    private fun isInsecureRemoteHttp(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        if (!lower.startsWith("http://")) return false
        val hostPart = lower.removePrefix("http://").substringBefore('/').substringBefore(':')
        val localHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        return hostPart !in localHosts
    }
}
