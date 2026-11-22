package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.ChequesApiClient
import red.man10.man10bank.api.model.request.ChequeCreateRequest
import red.man10.man10bank.api.model.response.Cheque
import red.man10.man10bank.api.model.request.ChequeUseRequest
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.ItemStackBase64
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
    private val featureToggles: FeatureToggleService,
) : Listener {

    private val idKey = NamespacedKey(plugin, "cheque_id")

    private val oldChequeKey = NamespacedKey.fromString("cheque_id")!!

    // 小切手金額の保存用キー（PDC: DOUBLE）
    private val amountKey = NamespacedKey(plugin, "cheque_amount")
    // 旧形式のキー（NamespacedKey.fromString("cheque_amount")）もサポート
    private val oldAmountKey = NamespacedKey.fromString("cheque_amount")!!

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // 右クリックのみを対象（左クリックでブロックを叩いただけで使われないようにする）
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val hasId = pdc.has(idKey, PersistentDataType.INTEGER) || pdc.has(oldChequeKey, PersistentDataType.INTEGER)
        if (!hasId) return

        if (!featureToggles.isEnabled(FeatureToggleService.Feature.CHEQUE)) {
            event.isCancelled = true
            Messages.error(plugin, player, "小切手機能は現在停止中です。")
            return
        }

        event.isCancelled = true
        // 使った小切手はこの時点で手から1枚取り上げて預かる。以前は応答を待ってから元の小切手を消していたため、
        // 待っている間に動かされる(移動・ドロップ等で別のアイテムになる)と消し損ね、使用済みなのに未使用に見える小切手が残っていた。
        val cheque = takeOneChequeFromHand(player, event.hand) ?: return
        scope.launch(Dispatchers.IO) {
            // 使用できたら使用済みの見た目の小切手を、できなかったら元の小切手を返す（どの経路でも消えないように finally で返す）
            var replacement: ItemStack? = null
            try {
                val result = useCheque(player, cheque)
                val detail = result.getOrNull()
                if (detail != null) {
                    replacement = buildChequeItem(cheque = detail, isUsed = true)
                    Messages.send(plugin, player, "小切手を使用しました。金額: ${BalanceFormats.coloredYen(detail.amount ?: 0.0)}")
                } else {
                    Messages.error(plugin, player, result.errorMessage("小切手の使用に失敗しました。"))
                }
            } finally {
                handBackCheque(player, replacement ?: cheque)
            }
        }
    }

    /**
     * 手に持っている小切手を1枚取り上げて返す。手のアイテムが小切手でなければ null。
     * メインスレッドで呼ぶこと。
     */
    private fun takeOneChequeFromHand(player: Player, hand: EquipmentSlot?): ItemStack? {
        val slot = hand ?: return null
        val inHand = player.inventory.getItem(slot)
        val pdc = inHand.itemMeta?.persistentDataContainer ?: return null
        if (!pdc.has(idKey, PersistentDataType.INTEGER) && !pdc.has(oldChequeKey, PersistentDataType.INTEGER)) return null
        val taken = inHand.asOne()
        player.inventory.setItem(slot, if (inHand.amount > 1) inHand.clone().apply { amount -= 1 } else null)
        return taken
    }

    /**
     * 預かっていた小切手（または使用済みの小切手）をプレイヤーへ渡す。
     * プラグイン停止中やプレイヤーがログアウト済みで渡せない場合は、運営が復旧できるよう小切手データを要確認ログへ残す（DESIGN 3.6）。
     */
    private fun handBackCheque(player: Player, cheque: ItemStack) {
        if (!plugin.isEnabled) {
            logLostCheque(player, cheque, "プラグイン停止中のため小切手を返せなかった")
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            val online = plugin.server.getPlayer(player.uniqueId)
            if (online == null) {
                logLostCheque(player, cheque, "プレイヤーがログアウト済みのため小切手を返せなかった")
                return@Runnable
            }
            online.inventory.addItem(cheque).values.forEach { online.world.dropItemNaturally(online.location, it) }
        })
    }

    private fun logLostCheque(player: Player, cheque: ItemStack, reason: String) {
        val encoded = runCatching { ItemStackBase64.encode(cheque) }.getOrElse { "エンコード失敗: ${it.message}" }
        plugin.logger.severe(
            "要手動対応[小切手返却失敗] uuid=${player.uniqueId} 詳細=${reason} 小切手データ(Base64)=${encoded}"
        )
    }

    /**
     * 小切手の作成。
     * - note が20文字以上の場合は失敗
     * - APIで作成し、返ってきたIDと金額をPDCに埋め込み
     * - null を返す場合は理由をプレイヤーへ通知済み（呼び出し側で重ねて通知しない）
     */
    suspend fun createCheque(p: Player, amount: Double, note: String?, isOP: Boolean): ItemStack? {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.CHEQUE)) {
            Messages.error(plugin, p, "小切手機能は現在停止中です。")
            return null
        }
        if (note != null && note.length >= 20) {
            Messages.error(plugin, p, "メモは20文字未満で指定してください。")
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
            // createCheque は suspend で非メインスレッドから呼ばれ得るため、メインへディスパッチする3引数版を使う（DESIGN 3.5）。
            Messages.error(plugin, p, "小切手の発行に失敗しました: $msg")
            return null
        }
        val cheque = created.getOrNull() ?: run {
            Messages.error(plugin, p, "小切手の発行に失敗しました。")
            return null
        }
        return buildChequeItem(cheque)
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

    /**
     * 小切手を使用する（use API を呼ぶだけで、アイテムの入れ替えは呼び出し元が行う）。
     * - PDCからIDを取得し、use APIを呼ぶ
     * - 成功時は使用した小切手の情報を返す
     */
    private suspend fun useCheque(user: Player, item: ItemStack): Result<Cheque> {
        val meta = item.itemMeta ?: return Result.failure(IllegalStateException("小切手のアイテム情報が不正です。"))
        val pdc = meta.persistentDataContainer
        val id = pdc.get(idKey, PersistentDataType.INTEGER)
            ?: pdc.get(oldChequeKey, PersistentDataType.INTEGER)
            ?: return Result.failure(IllegalStateException("小切手ではありません。"))

        val result = chequesApi.use(id, ChequeUseRequest(user.uniqueId.toString()))

        if (result.isSuccess) {
            val detail = result.getOrNull() ?: return Result.failure(IllegalStateException("小切手情報の取得に失敗しました。"))
            return Result.success(detail)
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
    private fun getChequeAmount(item: ItemStack?): Double? {
        val meta = item?.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        if (!pdc.has(idKey, PersistentDataType.INTEGER) && !pdc.has(oldChequeKey, PersistentDataType.INTEGER)) return null
        // 新形式（plugin namespace付き）優先
        pdc.get(amountKey, PersistentDataType.DOUBLE)?.let { return it }
        // 旧形式のキー（namespace無し）にも対応
        pdc.get(oldAmountKey, PersistentDataType.DOUBLE)?.let { return it }
        return null
    }
}
