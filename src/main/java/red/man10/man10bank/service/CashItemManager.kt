package red.man10.man10bank.service

import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import java.io.File
import java.io.IOException

/**
 * 現金アイテムの設定を管理するサービス。
 * - cash.yml への保存/読み込みを担当
 * - 複数種類の現金アイテムをサポート
 *
 * 保存形式（cash.yml）:
 * cashItems:
 *   "1000": <ItemStack>
 *   "500.5": <ItemStack>
 */
class CashItemManager(private val plugin: JavaPlugin) {

    // 金額キー（文字列化） -> アイテム
    private val items: MutableMap<String, ItemStack> = mutableMapOf()

    // PDCキー: 現金アイテムの金額
    private val cashAmountKey: NamespacedKey = NamespacedKey(plugin, "cash_amount")

    private val oldCashKey = NamespacedKey.fromString("money")!!

    /** 起動時等に一度呼んで、設定から全現金アイテムを読み込みます。 */
    fun load(): Map<String, ItemStack> {
        items.clear()

        val file = File(plugin.dataFolder, "cash.yml")
        if (!file.exists()) {
            file.createNewFile()
        }

        val config = YamlConfiguration.loadConfiguration(file)

        val sec = config.getConfigurationSection("cashItems")?: return emptyMap()
        for (key in sec.getKeys(false)) {
            val basePath = "cashItems.$key"
            val stack = config.getItemStack(basePath)
                ?: config.getItemStack("$basePath.0")
                ?: continue
            items[key] = stack.clone().asOne()
        }
        return items.toMap()
    }

    /** 設定へ保存（同額キーがあれば置き換え）。 */
    fun save(item: ItemStack, key: String) {
        val normalized = item.apply {
            editMeta { meta ->
                meta.persistentDataContainer.set(cashAmountKey, PersistentDataType.STRING, key)
            }
        }
        // メモリ更新
        items[key] = normalized.clone()
        // 設定へ反映
        val file = File(plugin.dataFolder, "cash.yml")
        if (!file.exists()) {
            file.createNewFile()
        }
        val config = YamlConfiguration.loadConfiguration(file)

        config.set("cashItems.$key", normalized)
        try { config.save(file) } catch (_: IOException) { }
    }

    /** 指定金額の現金アイテム（1個）を取得。未登録なら null。 */
    fun getItemForAmount(amount: Double): ItemStack? =
        items[amount.toInt().toString()]?.clone()?.asOne()

    /** 指定のアイテムが現金なら金額を返す。未登録なら null。 */
    fun getAmountForItem(item: ItemStack): Double? {
        val meta = item.itemMeta?: return null
        val pdc = meta.persistentDataContainer
        if (!pdc.has(cashAmountKey, PersistentDataType.STRING)) return null
        return pdc.get(cashAmountKey, PersistentDataType.STRING)?.toDoubleOrNull()
    }

    // -----------------
    // 集計ユーティリティ
    // -----------------
    /** プレイヤーのインベントリ内の現金合計を返す。 */
    fun countInventoryCash(player: org.bukkit.entity.Player): Double {
        var total = 0.0
        for (stack in player.inventory.contents) {
            val item = stack ?: continue
            if (item.amount <= 0 || item.type.isAir) continue
            val unit = getAmountForItem(item) ?: continue
            total += unit * item.amount
        }
        return total
    }

    /** プレイヤーのエンダーチェスト内の現金合計を返す。 */
    fun countEnderChestCash(player: org.bukkit.entity.Player): Double {
        var total = 0.0
        for (stack in player.enderChest.contents) {
            val item = stack ?: continue
            if (item.amount <= 0 || item.type.isAir) continue
            val unit = getAmountForItem(item) ?: continue
            total += unit * item.amount
        }
        return total
    }

    /** インベントリ+エンダーチェストの現金合計を返す。 */
    fun countTotalCash(player: org.bukkit.entity.Player): Double =
        countInventoryCash(player) + countEnderChestCash(player)

    /** 残高表示プロバイダの登録（現金）。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(
            id = "cash",
            order = 5,
            provider = { player ->
                val total = countTotalCash(player)
                if (total <= 0.0) "" else "§b§l現金: ${BalanceFormats.coloredYen(total)}§r"
            }
        )
    }
}
