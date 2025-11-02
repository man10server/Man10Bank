package red.man10.man10bank.service

/**
 * 各機能の有効/無効を管理するシンプルなトグル管理クラス。
 * プラグイン再起動で初期化される（永続化はしない）。
 */
class FeatureToggleService {

    enum class Feature(val key: String, val displayNameJa: String) {
        CHEQUE("cheque", "小切手"),
        TRANSACTION("transaction", "取引（入金/出金/送金）"),
        SERVER_LOAN("serverloan", "サーバーローン"),
        LOAN("loan", "プレイヤーローン"),
        ATM("atm", "ATM"),
    }

    private val disabled: MutableSet<Feature> = mutableSetOf()

    fun isEnabled(feature: Feature): Boolean = !disabled.contains(feature)

    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (enabled) disabled.remove(feature) else disabled.add(feature)
    }

    fun disableAll() { disabled.addAll(Feature.entries) }

    fun enableAll() { disabled.clear() }

    fun disabledFeatures(): List<Feature> = disabled.toList().sortedBy { it.ordinal }
}

