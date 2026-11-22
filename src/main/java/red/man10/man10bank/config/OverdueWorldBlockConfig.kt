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
                .map { normalize(it) }
                .filter { it.isNotEmpty() }
                .toSet()
            val message = section?.getString("message")?.takeIf { it.isNotBlank() } ?: DEFAULT_MESSAGE
            return OverdueWorldBlockConfig(worlds, message)
        }

        private fun normalize(worldName: String): String = worldName.trim().lowercase()
    }

    val enabled: Boolean
        get() = worlds.isNotEmpty()

    fun isBlocked(worldName: String): Boolean = normalize(worldName) in worlds

    /** 指定ワールドを対象に加えた設定を返す。 */
    fun withWorld(worldName: String): OverdueWorldBlockConfig = copy(worlds = worlds + normalize(worldName))

    /** 指定ワールドを対象から外した設定を返す。 */
    fun withoutWorld(worldName: String): OverdueWorldBlockConfig = copy(worlds = worlds - normalize(worldName))
}
