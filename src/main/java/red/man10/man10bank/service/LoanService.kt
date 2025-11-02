package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.ui.loan.CollateralCollectUI
import red.man10.man10bank.ui.loan.CollateralDebtorReleaseUI
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.ItemStackBase64
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

/**
 * プレイヤー間ローン機能のサービス（型のみ）。
 * - Listener を実装（イベントは後続で追加）
 * - ItemStack のBase64変換は ItemStackBase64 を利用
 */
class LoanService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: LoanApiClient,
    private val featureToggles: FeatureToggleService,
) : Listener {

    // 借金手形の識別用キー（PDC）
    private val loanIdKey = NamespacedKey(plugin, "loan_id")
    private val oldIdKey = NamespacedKey.fromString("id")!!

    /**
     * ローン作成。
     * - 成功/失敗のみ返す（メッセージ表示や手形発行などの副作用は本関数内で処理）
     * - collateral は任意（null 可）
     */
    suspend fun create(
        lender: Player,
        borrower: Player,
        repayAmount: Double,
        paybackInDays: Int,
        collaterals: List<ItemStack>?,
    ): Boolean {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.LOAN)) {
            Messages.error(plugin, lender, "プレイヤーローン機能は現在停止中です。")
            Messages.error(plugin, borrower, "プレイヤーローン機能は現在停止中です。")
            return false
        }
        if (repayAmount <= 0.0) {
            Messages.error(plugin, lender, "返済金額が不正です。0より大きい値を指定してください。")
            return false
        }
        if (paybackInDays <= 0) {
            Messages.error(plugin, lender, "返済期限日数が不正です。1以上を指定してください。")
            return false
        }

        val paybackDateIso = OffsetDateTime.now(ZoneOffset.UTC).plusDays(paybackInDays.toLong()).toString()
        val encoded = collaterals?.filter { !it.type.isAir }?.takeIf { it.isNotEmpty() }?.let { ItemStackBase64.encodeItems(it) }

        val body = LoanCreateRequest(
            lendUuid = lender.uniqueId.toString(),
            borrowUuid = borrower.uniqueId.toString(),
            amount = repayAmount,
            paybackDate = paybackDateIso,
            collateralItem = encoded,
        )
        val result = api.create(body)

        if (result.isFailure) {
            val msg = result.errorMessage()
            Messages.error(plugin, lender, "ローン作成に失敗しました: $msg")
            Messages.error(plugin, borrower, "借入が確定できませんでした。$msg")
            return false
        }

        val loan = result.getOrNull()
        if (loan == null) {
            Messages.error(plugin, lender, "ローン作成に失敗しました。 null")
            Messages.error(plugin, borrower, "借入が確定できませんでした。 null")
            return false
        }

        // 債権者に手形を発行し、双方に通知
        val note = issueDebtNote(loan)
        plugin.server.scheduler.runTask(plugin, Runnable {
            val leftover = lender.inventory.addItem(note)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { lender.world.dropItemNaturally(lender.location, it) }
            }
            Messages.send(plugin, lender, "ローン手形を発行しました。")
            Messages.send(plugin, borrower, "借入が確定しました。金額: ${BalanceFormats.coloredYen(repayAmount)}円")
        })
        return true
    }

    /**
     * 借り手のローン取得（プレイヤー単位）。
     * - APIの成功/失敗はサービス層で処理し、失敗時は空リストを返す。
     * - エラー時はプレイヤーに日本語で通知。
     */
    suspend fun getBorrowerLoans(player: Player, limit: Int = 100, offset: Int = 0): List<Loan> {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.LOAN)) {
            Messages.error(plugin, player, "プレイヤーローン機能は現在停止中です。")
            return emptyList()
        }
        val result = api.getBorrowerLoans(player.uniqueId, limit, offset)
        if (result.isSuccess) {
            return result.getOrNull().orEmpty()
        }
        val msg = result.errorMessage("借金一覧の取得に失敗しました。")
        Messages.error(plugin, player, msg)
        return emptyList()
    }

    /**
     * 担保返却の解放（借り手側）。
     * - APIの成功/失敗やUI表示までサービス層で処理する。
     */
    suspend fun releaseCollateral(id: Int, borrower: Player) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.LOAN)) {
            Messages.error(plugin, borrower, "プレイヤーローン機能は現在停止中です。")
            return
        }
        val result = api.releaseCollateral(id, borrower.uniqueId.toString())
        if (result.isFailure) {
            val msg = result.errorMessage("担保の解放に失敗しました。")
            Messages.error(plugin, borrower, msg)
            return
        }
        val loan = result.getOrNull()
        val base64 = loan?.collateralItem
        if (base64.isNullOrBlank()) {
            Messages.warn(plugin, borrower, "受け取れる担保がありません。")
            return
        }
        val items = ItemStackBase64.decodeItems(base64)
        if (items.isEmpty()) {
            Messages.warn(plugin, borrower, "担保データが不正です。")
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            CollateralDebtorReleaseUI(borrower, items, onReleased = {
                Messages.send(plugin, borrower, "担保の受け取りが完了しました。")
            }).open()
        })
    }


    @EventHandler(priority = EventPriority.NORMAL)
    fun onInteractEvent(event: PlayerInteractEvent) {
        val player = event.player
        val item = event.item ?: return
        val id = getLoanId(item) ?: return

        // 右クリックのみを対象
        val action = event.action
        val isRightClick = when (action) {
            org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> true
            else -> false
        }
        if (!isRightClick) return

        // 手形の使用を処理
        event.isCancelled = true
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.LOAN)) {
            Messages.error(plugin, player, "プレイヤーローン機能は現在停止中です。")
            return
        }
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repay(player, id, item)
        }
    }

    private suspend fun repay(collector: Player, id: Int, usedItem: ItemStack?) {
        val result = api.repay(id, collector.uniqueId.toString())

        if (!result.isSuccess) {
            val msg = result.errorMessage()
            Messages.error(plugin, collector, "返済処理に失敗しました: $msg")
            return
        }
        val resp = result.getOrNull()
        if (resp == null) {
            Messages.error(plugin, collector, "返済処理に失敗しました。")
            return
        }
        when (resp.outcome) {
            0 -> {
                val collected = resp.collectedAmount
                val remaining = resp.remainingAmount
                Messages.send(plugin, collector, "返済を回収しました。金額: ${BalanceFormats.coloredYen(collected)} 残額: ${BalanceFormats.coloredYen(remaining)}")
            }
            1 -> {
                val base64 = resp.collateralItem
                if (base64.isNullOrBlank()) {
                    Messages.warn(plugin, collector, "担保データが見つかりませんでした。")
                    return
                }
                val items = ItemStackBase64.decodeItems(base64)
                if (items.isEmpty()) {
                    Messages.warn(plugin, collector, "担保アイテムが空です。")
                    return
                }
                // GUI操作はメインスレッドで行う
                plugin.server.scheduler.runTask(plugin, Runnable {
                    CollateralCollectUI(collector, items, onCollected = {
                        Messages.send(plugin, collector, "担保を回収しました。")
                    }).open()
                })
            }
            else -> {
                Messages.error(plugin, collector, "返済処理で不明な状態です。(outcome=${resp.outcome})")
            }
        }

        // 返済後に最新のローン情報で手形を更新
        val loanRes = api.get(id)
        if (!loanRes.isSuccess) {
            val msg = loanRes.errorMessage()
            Messages.error(plugin, collector, "最新のローン情報の取得に失敗しました: $msg")
            return
        }
        val loan = loanRes.getOrNull() ?: return

        val updatedNote = issueDebtNote(loan)
        plugin.server.scheduler.runTask(plugin, Runnable {
            usedItem?.amount = 0
            val leftover = collector.inventory.addItem(updatedNote)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { collector.world.dropItemNaturally(collector.location, it) }
            }
            Messages.send(plugin, collector, "手形を更新しました。")
        })
    }

    private fun issueDebtNote(loan: Loan): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta
        meta.setCustomModelData(2)
        meta.displayName(Component.text("§c§l約束手形 §7§l(Promissory Note)"))

        val borrowerName = Bukkit.getOfflinePlayer(UUID.fromString(loan.borrowUuid)).name
        val paybackText = DateFormats.toDate(loan.paybackDate!!)
        val hasCollateral = !loan.collateralItem.isNullOrBlank()
        val debt = BalanceFormats.amount(loan.amount?:0.0)
        val lore = listOf(
            Component.text("§4§l========[Man10Bank]========"),
            Component.text("   §7§l債務者:  $borrowerName"),
            Component.text("   §8§l有効日:  $paybackText"),
            Component.text("   §7§l支払額:  $debt 円" ),
            Component.text("   §8§l担保等:  ${if (hasCollateral) "あり" else "なし"}"),
            Component.text("§4§l==========================")
        )
        meta.lore(lore)
        meta.persistentDataContainer.set(loanIdKey, PersistentDataType.INTEGER, loan.id ?: -1)

        meta.addEnchant(Enchantment.FORTUNE, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)

        item.itemMeta = meta
        return item
    }

    /**
     * 手形Itemから Loan ID を取得（"loan_id" / 旧形式 "id" をサポート）。
     */
    private fun getLoanId(item: ItemStack?): Int? {
        val meta = item?.itemMeta ?: return null
        val pdc = meta.persistentDataContainer
        pdc.get(loanIdKey, PersistentDataType.INTEGER)?.let { return it }
        pdc.get(oldIdKey, PersistentDataType.INTEGER)?.let { return it }
        return null
    }
}
