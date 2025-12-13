package red.man10.man10bank.service

import org.bukkit.plugin.java.JavaPlugin

/**
 * 各機能の有効/無効を管理し、config.yml に永続化するトグル管理クラス。
 */
class FeatureToggleService(private val plugin: JavaPlugin) {

    enum class Feature(val key: String, val displayNameJa: String) {
        CHEQUE("cheque", "小切手"),
        TRANSACTION("transaction", "取引（入金/出金/送金）"),
        SERVER_LOAN("serverloan", "Man10リボ"),
        LOAN("loan", "プレイヤーローン"),
        ATM("atm", "ATM");

        companion object {
            fun fromArg(arg: String): Feature? = when (arg.lowercase()) {
                "cheque", "小切手" -> CHEQUE
                "transaction", "取引" -> TRANSACTION
                "serverloan", "server-loan", "server_loan", "サーバーローン" -> SERVER_LOAN
                "loan", "プレイヤーローン" -> LOAN
                "atm" -> ATM
                else -> null
            }
        }
    }

    private val disabled: MutableSet<Feature> = mutableSetOf()

    init {
        loadFromConfig()
    }

    fun isEnabled(feature: Feature): Boolean = !disabled.contains(feature)

    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (enabled) disabled.remove(feature) else disabled.add(feature)
        saveToConfig()
    }

    fun disableAll() {
        disabled.addAll(Feature.entries)
        saveToConfig()
    }

    fun enableAll() {
        disabled.clear()
        saveToConfig()
    }

    fun disabledFeatures(): List<Feature> = disabled.toList().sortedBy { it.ordinal }

    /** config.yml からトグル状態を読み込む。未設定は true(=有効) として扱う。 */
    fun loadFromConfig() {
        val conf = plugin.config
        disabled.clear()
        Feature.entries.forEach { f ->
            val enabled = conf.getBoolean("features.${f.key}", true)
            if (!enabled) disabled.add(f)
        }
    }

    /** 現在のトグル状態を config.yml に保存する。 */
    fun saveToConfig() {
        val conf = plugin.config
        Feature.entries.forEach { f ->
            conf.set("features.${f.key}", isEnabled(f))
        }
        plugin.saveConfig()
    }
}
