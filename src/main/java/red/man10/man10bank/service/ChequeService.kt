package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
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
import red.man10.man10bank.api.model.request.ChequeUseRequest
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import kotlin.math.floor

/**
 * 小切手(cheque)関連のイベントハンドラ。
 * - 仕様は今後拡張。現状は土台のみ。
 */
class ChequeService(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val chequesApi: ChequesApiClient,
) : Listener {

    private val idKey = NamespacedKey(plugin, "cheque_id")

    private val oldChequeKey = NamespacedKey.fromString("cheque_id")!!

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val hasId = pdc.has(idKey, PersistentDataType.INTEGER) || pdc.has(oldChequeKey, PersistentDataType.INTEGER)
        if (!hasId) return

        event.isCancelled = true
        scope.launch(Dispatchers.IO) {
            val amount = useCheque(player, item)
            plugin.server.scheduler.runTask(plugin, Runnable {
                Messages.send(player, "小切手を使用しました。金額: ${BalanceFormats.colored(amount)}")
            })
        }
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
            amount = floor(amount),
            note = note,
            op = isOP
        )
        val created = chequesApi.create(req)
        if (created.isFailure) return null
        val cheque = created.getOrNull()?:return null
        return buildChequeItem(cheque)
    }

    /**
     * 小切手を使用する。
     * - PDCからIDを取得し、use APIを呼ぶ
     * - 成功、または既に使用済みなら使用済みの見た目の小切手に置き換えたItemStackを返す
     * - そうでなければ null
     */
    private suspend fun useCheque(user: Player, item: ItemStack): Double {
        val meta = item.itemMeta ?: return 0.0
        val pdc = meta.persistentDataContainer
        val id = pdc.get(idKey, PersistentDataType.INTEGER)
            ?: pdc.get(oldChequeKey, PersistentDataType.INTEGER)
            ?: return 0.0

        val result = chequesApi.use(id, ChequeUseRequest(user.uniqueId.toString()))

        if (result.isSuccess) {
            val detail = result.getOrNull()?:return 0.0
            Bukkit.getScheduler().runTask(plugin, Runnable {
                item.amount = 0
                user.inventory.addItem(buildChequeItem(cheque = detail, isUsed = true))
            })
            return detail.amount!!
        }
        return 0.0
    }

    private fun buildChequeItem(cheque: Cheque, isUsed: Boolean = false): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta

        meta.setCustomModelData(1)
        meta.displayName(Component.text("§b§l小切手§7§l(Cheque)"))

        val lore = mutableListOf(
            Component.text("§e====[Man10Bank]===="),
            Component.text(""),
            Component.text("§a§l発行者: ${if (cheque.op) "§c§l" else "§d§l"}${cheque.player}"),
            Component.text("§a§l金額: ${BalanceFormats.amount(cheque.amount?:0.0)}円"),
        )

        if (!cheque.note.isNullOrBlank()) lore.add(Component.text("§d§lメモ: ${cheque.note}"))
        if (isUsed) lore.add(Component.text("  §c§o[使用済み]  "))
        lore.add(Component.text(""))
        lore.add(Component.text("§e=================="))

        meta.lore(lore)

        meta.addEnchant(Enchantment.FORTUNE, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)

        if (isUsed){
            item.itemMeta = meta
            return item
        }
        meta.persistentDataContainer.set(idKey, PersistentDataType.INTEGER, cheque.id?:-1)
        item.itemMeta = meta
        return item
    }
}
