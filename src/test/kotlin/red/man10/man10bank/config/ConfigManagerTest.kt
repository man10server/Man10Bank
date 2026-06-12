package red.man10.man10bank.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.config.ConfigManager.ApiConfig
import java.util.logging.Logger

/**
 * ConfigManager の設定読み取りテスト。
 *
 * ConfigManager は JavaPlugin に依存するが、設定パース本体である private な
 * readApiConfig(FileConfiguration, Logger) は plugin フィールドを参照しないため、
 * リフレクションで直接呼び出し、Bukkitサーバー（MockBukkit）に依存せず検証する。
 * YamlConfiguration は純粋なYAMLパーサとして単体で利用でき、サーバー起動を要しない。
 * Logger は匿名ロガーを渡すことで警告経路でも例外にならない。
 */
@DisplayName("ConfigManager のテスト")
class ConfigManagerTest {

    // 警告メッセージの出力先（テスト中は破棄してよい）。
    private val testLogger: Logger = Logger.getAnonymousLogger()

    // private readApiConfig(FileConfiguration, Logger): ApiConfig をリフレクションで呼ぶ。
    private fun readApiConfig(yaml: String): ApiConfig {
        val conf: FileConfiguration = YamlConfiguration().apply { loadFromString(yaml) }
        val method = ConfigManager::class.java.getDeclaredMethod(
            "readApiConfig", FileConfiguration::class.java, Logger::class.java,
        )
        method.isAccessible = true
        // readApiConfig は plugin フィールドを参照しないため、コンストラクタを通さず
        // インスタンスのみ確保する（Kotlinの非null引数チェックを回避するため Unsafe を使う）。
        val instance = allocateConfigManager()
        return method.invoke(instance, conf, testLogger) as ApiConfig
    }

    // コンストラクタ（非null引数チェック付き）を通さずに ConfigManager の空インスタンスを確保する。
    private fun allocateConfigManager(): ConfigManager {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(ConfigManager::class.java) as ConfigManager
    }

    @Test
    @DisplayName("timeout/retries未指定時はデフォルト値が適用される")
    fun usesDefaultsWhenOmitted() {
        val yaml = """
            api:
              baseUrl: "https://api.example.com"
              apiKey: "token"
        """.trimIndent()
        val config = readApiConfig(yaml)
        assertEquals("https://api.example.com", config.baseUrl)
        assertEquals("token", config.apiKey)
        assertEquals(ConfigManager.DEFAULT_REQUEST_MS, config.timeouts.requestMs)
        assertEquals(ConfigManager.DEFAULT_CONNECT_MS, config.timeouts.connectMs)
        assertEquals(ConfigManager.DEFAULT_SOCKET_MS, config.timeouts.socketMs)
        assertEquals(ConfigManager.DEFAULT_RETRIES, config.retries)
    }

    @Test
    @DisplayName("timeout/retriesが指定された値で読み込まれる")
    fun readsExplicitValues() {
        val yaml = """
            api:
              baseUrl: "https://api.example.com"
              apiKey: "token"
              timeout:
                requestMs: 5000
                connectMs: 1500
                socketMs: 7000
              retries: 4
        """.trimIndent()
        val config = readApiConfig(yaml)
        assertEquals(5000L, config.timeouts.requestMs)
        assertEquals(1500L, config.timeouts.connectMs)
        assertEquals(7000L, config.timeouts.socketMs)
        assertEquals(4, config.retries)
    }

    @Test
    @DisplayName("apiKeyが空文字の場合はnullになる")
    fun blankApiKeyBecomesNull() {
        val yaml = """
            api:
              baseUrl: "https://api.example.com"
              apiKey: ""
        """.trimIndent()
        val config = readApiConfig(yaml)
        assertNull(config.apiKey, "apiKey空文字はnullへ正規化される")
    }
}
