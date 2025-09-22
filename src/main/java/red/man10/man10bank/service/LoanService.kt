package red.man10.man10bank.service

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.ItemStackBase64
import red.man10.man10bank.ui.loan.CollateralCollectUI
import red.man10.man10bank.util.Messages
import java.text.SimpleDateFormat
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
    private val api: LoanApiClient,
) : Listener {

    // 借金手形の識別用キー（PDC）
    private val loanIdKey = NamespacedKey(plugin, "loan_id")
    private val oldIdKey = NamespacedKey.fromString("id")!!

    /**
     * ローン作成。
     * - 返却: 作成された Loan
     * - collateral は任意（null 可）
     */
    suspend fun create(
        lender: Player,
        borrower: Player,
        repayAmount: Double,
        paybackInDays: Int,
        collaterals: List<ItemStack>?,
    ): Result<Loan> {
        if (repayAmount <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。正の数を指定してください。"))
        if (paybackInDays <= 0) return Result.failure(IllegalArgumentException("返済期限日数が不正です。1以上を指定してください。"))

        val paybackDateIso = OffsetDateTime.now(ZoneOffset.UTC).plusDays(paybackInDays.toLong()).toString()
        val encoded = collaterals?.filter { !it.type.isAir }?.takeIf { it.isNotEmpty() }?.let { ItemStackBase64.encodeItems(it) }

        val body = LoanCreateRequest(
            lendUuid = lender.uniqueId.toString(),
            borrowUuid = borrower.uniqueId.toString(),
            amount = repayAmount,
            paybackDate = paybackDateIso,
            collateralItem = encoded,
        )
        return api.create(body)
    }

    /**
     * ローン詳細の取得。
     */
    suspend fun get(id: Int): Result<Loan> = api.get(id)

    /**
     * 借り手のローン取得（プレイヤー単位）。
     */
    suspend fun getBorrowerLoans(player: Player, limit: Int = 100, offset: Int = 0): Result<List<Loan>> =
        api.getBorrowerLoans(player.uniqueId, limit, offset)

    /**
     * 担保返却の解放。
     * - borrower は借り手（任意）。null の場合はAPIへ未指定で委譲
     */
    suspend fun releaseCollateral(id: Int, borrower: Player): Result<Loan> =
        api.releaseCollateral(id, borrower.uniqueId.toString())

    private suspend fun repay(collector: Player, id: Int) {
        val result = api.repay(id, collector.uniqueId.toString())

        if (!result.isSuccess) {
            val msg = result.exceptionOrNull()?.message ?: "返済処理に失敗しました。"
            val notFound = msg.contains("404") || msg.contains("Not Found", ignoreCase = true)
            if (notFound) {
                Messages.error(plugin, collector, "借金データが見つかりません。(id: ${id})")
            } else {
                Messages.error(plugin, collector, msg)
            }
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
                Messages.send(plugin, collector, "返済を回収しました。金額: ${BalanceFormats.colored(collected)} 残額: ${BalanceFormats.colored(remaining)}")
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
                CollateralCollectUI(collector, items, onCollected = {
                    Messages.send(plugin, collector, "担保を回収しました。")
                }).open()
            }
            else -> {
                Messages.error(plugin, collector, "返済処理で不明な状態です。(outcome=${resp.outcome})")
            }
        }
    }

    private fun issueDebtNote(loan: Loan): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta
        meta.setCustomModelData(2)
        meta.displayName(Component.text("§c§l約束手形 §7§l(Promissory Note)"))

        val sdf = SimpleDateFormat("yyyy-MM-dd")
        val borrowerName = Bukkit.getOfflinePlayer(UUID.fromString(loan.borrowUuid)).name
        val paybackText = sdf.format(loan.paybackDate!!)
        val hasCollateral = !loan.collateralItem.isNullOrBlank()
        val debt = BalanceFormats.amount(loan.amount?:0.0)
        val lore = listOf(
            Component.text("§4§l========[Man10Bank]========"),
            Component.text("   §7§l債務者:  $borrowerName"),
            Component.text("   §8§l有効日:  $paybackText"),
            Component.text("   §7§l支払額:  $debt 円${if (hasCollateral) " or 担保アイテム" else ""}"),
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
