package red.man10.man10bank.service

import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import kotlin.math.floor

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
class CashItemManager(plugin: JavaPlugin) {

    private val file: File = File(plugin.dataFolder, "cash.yml")
    private var config: YamlConfiguration = YamlConfiguration()

    // 金額キー（文字列化） -> アイテム
    private val items: MutableMap<String, ItemStack> = mutableMapOf()

    // PDCキー: 現金アイテムの金額
    private val cashAmountKey: NamespacedKey = NamespacedKey(plugin, "cash_amount")

    /** 起動時等に一度呼んで、設定から全現金アイテムを読み込みます。 */
    fun load(): Map<String, ItemStack> {
        items.clear()

        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }

        config = YamlConfiguration()
        try { config.load(file) } catch (_: Exception) { config = YamlConfiguration() }

        val sec = config.getConfigurationSection("cashItems")
        if (sec != null) {
            for (key in sec.getKeys(false)) {
                val stack = sec.getItemStack(key) ?: continue
                items[key] = stack.clone().asOne()
            }
        }

        return items.toMap()
    }

    /** 設定へ保存（同額キーがあれば置き換え）。 */
    fun save(item: ItemStack, amount: Double) {
        val key = amountKey(amount)
        val normalized = item.apply {
            editMeta { meta ->
                meta.persistentDataContainer.set(cashAmountKey, PersistentDataType.STRING, key)
            }
        }
        // メモリ更新
        items[key] = normalized.clone()
        // 設定へ反映
        config.set("cashItems.$key", normalized)
        try { config.save(file) } catch (_: IOException) { }
    }

    /** 指定金額の現金アイテム（1個）を取得。未登録なら null。 */
    fun getItemForAmount(amount: Double): ItemStack? =
        items[amountKey(amount)]?.clone()?.asOne()

    /** 指定のアイテムが現金なら金額を返す。未登録なら null。 */
    fun getAmountForItem(item: ItemStack): Double? {
        val meta = item.itemMeta?: return null
        val pdc = meta.persistentDataContainer
        if (!pdc.has(cashAmountKey, PersistentDataType.STRING)) return null
        return pdc.get(cashAmountKey, PersistentDataType.STRING)?.toDoubleOrNull()
    }

    /** 登録済みの現金アイテム一覧を金額→アイテムで取得。 */
    fun getRegisteredCashItems(): Map<Double, ItemStack> {
        val result = mutableMapOf<Double, ItemStack>()
        for ((key, stack) in items) {
            val amount = key.toDoubleOrNull() ?: continue
            result[amount] = stack.clone().asOne()
        }
        return result
    }

    private fun amountKey(amount: Double): String =
        floor(amount).toString()
}
