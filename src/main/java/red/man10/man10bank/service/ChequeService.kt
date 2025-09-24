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
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.Messages
import kotlin.math.floor

/**
 * 小切手(cheque)関連のイベントハンドラ。
 */
class ChequeService(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val chequesApi: ChequesApiClient,
) : Listener {

    private val idKey = NamespacedKey(plugin, "cheque_id")

    private val oldChequeKey = NamespacedKey.fromString("cheque_id")!!

    // 小切手金額の保存用キー（PDC: DOUBLE）
    private val amountKey = NamespacedKey(plugin, "cheque_amount")

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
            val result = useCheque(player, item)
            if (result.isSuccess) {
                val amount = result.getOrNull() ?: 0.0
                Messages.send(plugin, player, "小切手を使用しました。金額: ${BalanceFormats.coloredYen(amount)}")
            } else {
                val msg = result.errorMessage("小切手の使用に失敗しました。")
                Messages.error(plugin, player, msg)
            }
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
        if (created.isFailure) {
            val msg = created.errorMessage()
            Messages.error(p, "小切手の発行に失敗しました: $msg")
            return null
        }
        val cheque = created.getOrNull()?:return null
        return buildChequeItem(cheque)
    }

    /**
     * 小切手を使用する。
     * - PDCからIDを取得し、use APIを呼ぶ
     * - 成功、または既に使用済みなら使用済みの見た目の小切手に置き換えたItemStackを返す
     * - そうでなければ null
     */
    private suspend fun useCheque(user: Player, item: ItemStack): Result<Double> {
        val meta = item.itemMeta ?: return Result.failure(IllegalStateException("小切手のアイテム情報が不正です。"))
        val pdc = meta.persistentDataContainer
        val id = pdc.get(idKey, PersistentDataType.INTEGER)
            ?: pdc.get(oldChequeKey, PersistentDataType.INTEGER)
            ?: return Result.failure(IllegalStateException("小切手ではありません。"))

        val result = chequesApi.use(id, ChequeUseRequest(user.uniqueId.toString()))

        if (result.isSuccess) {
            val detail = result.getOrNull() ?: return Result.failure(IllegalStateException("小切手情報の取得に失敗しました。"))
            Bukkit.getScheduler().runTask(plugin, Runnable {
                item.amount = 0
                user.inventory.addItem(buildChequeItem(cheque = detail, isUsed = true))
            })
            return Result.success(detail.amount ?: 0.0)
        }
        return Result.failure(result.exceptionOrNull() ?: RuntimeException("小切手の使用に失敗しました。"))
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
        // 小切手の額面をPDCに保存（DOUBLE）。未指定時は0として扱う。
        meta.persistentDataContainer.set(amountKey, PersistentDataType.DOUBLE, cheque.amount ?: 0.0)
        item.itemMeta = meta
        return item
    }

    /**
     * ItemStack から小切手の金額を取得。小切手でない/金額未格納なら null。
     */
    fun getChequeAmount(item: ItemStack?): Double? {
        val meta = item?.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        if (!pdc.has(idKey, PersistentDataType.INTEGER) && !pdc.has(oldChequeKey, PersistentDataType.INTEGER)) return null
        return pdc.get(amountKey, PersistentDataType.DOUBLE)
    }

    /**
     * プレイヤーのインベントリとエンダーチェストにある小切手の総額を返す。
     */
    fun countTotalChequeAmount(player: Player): Double {
        var total = 0.0
        // インベントリ
        for (stack in player.inventory.contents) {
            val s = stack ?: continue
            val amount = getChequeAmount(s) ?: continue
            if (s.amount > 0) total += amount * s.amount
        }
        // エンダーチェスト
        for (stack in player.enderChest.contents) {
            val s = stack ?: continue
            val amount = getChequeAmount(s) ?: continue
            if (s.amount > 0) total += amount * s.amount
        }
        return total
    }
}
