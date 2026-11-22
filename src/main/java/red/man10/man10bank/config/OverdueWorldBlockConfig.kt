package red.man10.man10bank.config

import org.bukkit.configuration.file.FileConfiguration

/**
 * サーバーローン延滞者のワールド進入制限の設定（config.yml の overdueWorldBlock）。
 * - worlds が空の場合は機能全体が無効。
 * - ワールド名は大文字小文字を区別しない。
 */
data class OverdueWorldBlockConfig(
    val worlds: Set<String>,
    val message: String,
) {

    companion object {
        /** 進入を拒否・キックしたときに表示する文言の既定値。 */
        const val DEFAULT_MESSAGE = "あなたは延滞者のためこのエリアには入れません"

        fun from(conf: FileConfiguration): OverdueWorldBlockConfig {
            val section = conf.getConfigurationSection("overdueWorldBlock")
            val worlds = section?.getStringList("worlds").orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val message = section?.getString("message")?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE
            return OverdueWorldBlockConfig(worlds, message)
        }
    }

    val enabled: Boolean
        get() = worlds.isNotEmpty()

    fun isBlocked(worldName: String): Boolean = worldName.lowercase() in worlds
}
