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
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * 小切手(cheque)関連のイベントハンドラ。
 * - 仕様は今後拡張。現状は土台のみ。
 */
class ChequeService(
    private val plugin: JavaPlugin,
    private val chequesApi: ChequesApiClient,
) : Listener {

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
    suspend fun createCheque(p: Player, amount: Double, note: String?, isOP: Boolean): Result<ItemStack> {
        if (note != null && note.length >= 20) {
            return Result.failure(IllegalArgumentException("メモは20文字未満で指定してください。"))
        }
        val req = ChequeCreateRequest(
            uuid = p.uniqueId.toString(),
            amount = amount,
            note = note
        )
        val created = chequesApi.create(req)
        if (created.isFailure) return Result.failure(created.exceptionOrNull()!!)
        val cheque = created.getOrNull()
        val id = cheque?.id ?: return Result.failure(IllegalStateException("小切手IDの取得に失敗しました"))

        val chequeItem = ItemStack(Material.PAPER)
        val meta = chequeItem.itemMeta
        meta.setCustomModelData(1)

        meta.displayName(Component.text("§b§l小切手§7§l(Cheque)"))

        val lore = mutableListOf<Component>()
        lore.add(Component.text("§e====[Man10Bank]===="))
        lore.add(Component.text(""))
        lore.add(Component.text("§a§l発行者: ${if (isOP) "§c§l" else "§d§l"}${p.name}"))
        lore.add(Component.text("§a§l金額: ${BalanceFormats.amount(amount)}円"))
        if (note != null) lore.add(Component.text("§d§lメモ: $note"))
        lore.add(Component.text(""))
        lore.add(Component.text("§e=================="))

        meta.lore(lore)

        meta.addEnchant(Enchantment.DURABILITY, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE)

        meta.persistentDataContainer.set(NamespacedKey.fromString("cheque_id")!!, PersistentDataType.INTEGER, id)
        meta.persistentDataContainer.set(NamespacedKey.fromString("cheque_amount")!!, PersistentDataType.DOUBLE, amount)

        chequeItem.itemMeta = meta
        return Result.success(chequeItem)
    }
}
