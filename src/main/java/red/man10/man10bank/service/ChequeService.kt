package red.man10.man10bank.service

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.ChequesApiClient
import red.man10.man10bank.api.model.request.ChequeCreateRequest
import red.man10.man10bank.api.model.response.Cheque
import red.man10.man10bank.util.BalanceFormats

/**
 * 小切手(cheque)関連のイベントハンドラ。
 * - 仕様は今後拡張。現状は土台のみ。
 */
class ChequeService(
    private val plugin: JavaPlugin,
    private val chequesApi: ChequesApiClient,
) : Listener {

    private val idKey = NamespacedKey(plugin, "cheque_id")
    private val amountKey = NamespacedKey(plugin, "cheque_amount")

    private val oldChequeKey = NamespacedKey.fromString("cheque_id")!!
    private val oldAmountKey = NamespacedKey.fromString("cheque_amount")!!

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // TODO: 小切手アイテムの右クリック使用などの処理をここに実装
        // 現状は土台のみで処理は行わない
    }

    /**
     * 小切手の作成。
     * - note が20文字以上の場合は失敗
     * - APIで作成し、返ってきたIDと金額をPDCに埋め込み
     */
    suspend fun createCheque(p: Player, amount: Double, note: String?, isOP: Boolean): ItemStack? {
        if (note != null && note.length >= 20) {
            return null
        }
        val req = ChequeCreateRequest(
            uuid = p.uniqueId.toString(),
            amount = amount,
            note = note,
            op = isOP
        )
        val created = chequesApi.create(req)
        if (created.isFailure) return null
        val cheque = created.getOrNull()?:return null
        return buildChequeItem(cheque)
    }

    private fun buildChequeItem(cheque: Cheque, isUsed: Boolean = false): ItemStack {
        return ItemStack(Material.PAPER).apply {
            editMeta { meta ->
                val cmd = meta.customModelDataComponent
                cmd.floats.add(1.0F)
                meta.setCustomModelDataComponent(cmd)

                meta.displayName(Component.text("§b§l小切手§7§l(Cheque)"))

                val lore = mutableListOf(
                    Component.text("§e====[Man10Bank]===="),
                    Component.text(""),
                    Component.text("§a§l発行者: ${if (cheque.op) "§c§l" else "§d§l"}${cheque.player}"),
                    Component.text("§a§l金額: ${BalanceFormats.amount(cheque.amount?:0.0)}円"),
                    Component.text("§d§lメモ: ${cheque.note ?: "なし"}"),
                    if (isUsed) Component.text("§c§o[使用済み]") else Component.text(""),
                    Component.text("§e==================")
                )
                meta.lore(lore)
                meta.addEnchant(Enchantment.FORTUNE, 0, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

                if (isUsed) return@editMeta

                meta.persistentDataContainer.set(idKey, PersistentDataType.INTEGER, cheque.id?:-1)
                meta.persistentDataContainer.set(amountKey, PersistentDataType.DOUBLE, cheque.amount?:0.0)
            }
        }
    }
}
