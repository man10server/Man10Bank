package red.man10.man10bank.service

import org.bukkit.entity.Player
import org.bukkit.NamespacedKey
import org.bukkit.event.Listener
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.ItemFlag
import net.kyori.adventure.text.Component
import red.man10.man10bank.ui.loan.CollateralViewUI
import red.man10.man10bank.util.ItemStackBase64
import red.man10.man10bank.util.Messages
import java.text.SimpleDateFormat
import kotlin.math.floor
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.util.ItemStackBase64
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * プレイヤー間ローン機能のサービス（型のみ）。
 * - Listener を実装（イベントは後続で追加）
 * - ItemStack のBase64変換は ItemStackBase64 を利用
 */
class LoanService(
    private val plugin: Man10Bank,
    private val api: LoanApiClient,
) : Listener {

    // 借金手形の識別用キー
    private val debtKey = NamespacedKey(plugin, "loan_debt_note")
    private val debtBorrowerKey = NamespacedKey(plugin, "loan_debt_borrower")
    private val debtAmountKey = NamespacedKey(plugin, "loan_debt_amount")
    private val debtPaybackKey = NamespacedKey(plugin, "loan_debt_payback")
    private val debtCollateralKey = NamespacedKey(plugin, "loan_debt_collateral_b64")

    /**
     * 借り手のローン取得（プレイヤー単位）。
     */
    suspend fun getBorrowerLoans(player: Player, limit: Int = 100, offset: Int = 0): Result<List<Loan>> =
        api.getBorrowerLoans(player.uniqueId, limit, offset)

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
        val body = LoanCreateRequest(
            lendUuid = lender.uniqueId.toString(),
            borrowUuid = borrower.uniqueId.toString(),
            amount = repayAmount,
            paybackDate = paybackDateIso,
            collateralItem = encodeCollateralsOrNull(collaterals),
        )
        return api.create(body)
    }

    /**
     * ローン詳細の取得。
     */
    suspend fun get(id: Int): Result<Loan> = api.get(id)

    /**
     * 返済実行。
     * - collector は回収者（任意）。null の場合はAPIへ未指定で委譲
     */
    suspend fun repay(id: Int, collector: Player): Result<Loan> =
        api.repay(id, collector.uniqueId.toString())

    /**
     * 担保返却の解放。
     * - borrower は借り手（任意）。null の場合はAPIへ未指定で委譲
     */
    suspend fun releaseCollateral(id: Int, borrower: Player): Result<Loan> =
        api.releaseCollateral(id, borrower.uniqueId.toString())

    // --------------
    // 内部ユーティリティ
    // --------------
    /**
     * 担保アイテムをBase64へ変換（必要時に使用）。
     */
    internal fun encodeCollateralsOrNull(items: List<ItemStack>?): String? =
        items?.filter { !it.type.isAir }?.takeIf { it.isNotEmpty() }?.let { ItemStackBase64.encodeItems(it) }

    /**
     * 借金手形の発行（ItemStack を生成）。
     * - 種類: PAPER, CustomModelData=2
     * - Lore は指定フォーマットで生成
     * - PDC に各種情報を埋め込む（使用時に参照）
     */
    fun issueDebtNote(borrow: UUID, debt: Double, paybackDate: Date, collateralItems: List<ItemStack>?): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta
        meta.setCustomModelData(2)
        meta.displayName(Component.text("§4§l借金手形"))

        val sdf = SimpleDateFormat("yyyy-MM-dd")
        val hasCollateral = !collateralItems.isNullOrEmpty()
        val lore = listOf(
            Component.text("§4§l========[Man10Bank]========"),
            Component.text("   §7§l債務者:  ${Bukkit.getOfflinePlayer(borrow).name}"),
            Component.text("   §8§l有効日:  ${sdf.format(paybackDate)}"),
            Component.text("   §7§l支払額:  ${floor(debt)} 円${if (hasCollateral) " or 担保アイテム" else ""}"),
            Component.text("§4§l==========================")
        )
        meta.lore(lore)

        // PDC 埋め込み
        val pdc = meta.persistentDataContainer
        pdc.set(debtKey, PersistentDataType.BYTE, 1)
        pdc.set(debtBorrowerKey, PersistentDataType.STRING, borrow.toString())
        pdc.set(debtAmountKey, PersistentDataType.DOUBLE, floor(debt))
        pdc.set(debtPaybackKey, PersistentDataType.LONG, paybackDate.time)
        collateralItems?.takeIf { it.isNotEmpty() }?.let {
            val b64 = ItemStackBase64.encodeItems(it)
            pdc.set(debtCollateralKey, PersistentDataType.STRING, b64)
        }

        // 軽い演出（隠しエンチャ）
        meta.addEnchant(Enchantment.LUCK, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)

        item.itemMeta = meta
        return item
    }

    /**
     * 借金手形の使用処理。
     * - 右クリックで情報を表示。担保があれば参照GUIを開く。
     */
    @EventHandler
    fun onDebtNoteUse(event: PlayerInteractEvent) {
        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        if (!pdc.has(debtKey, PersistentDataType.BYTE)) return

        event.isCancelled = true
        val player = event.player

        val borrowerUuid = pdc.get(debtBorrowerKey, PersistentDataType.STRING)
        val amount = pdc.get(debtAmountKey, PersistentDataType.DOUBLE)
        val payback = pdc.get(debtPaybackKey, PersistentDataType.LONG)

        val borrowerName = try {
            borrowerUuid?.let { Bukkit.getOfflinePlayer(UUID.fromString(it)).name } ?: "不明"
        } catch (e: Exception) { "不明" }

        val dateText = try { SimpleDateFormat("yyyy-MM-dd").format(Date(payback ?: 0L)) } catch (_: Exception) { "不明" }
        val amtText = amount?.let { floor(it) }?.toString() ?: "不明"

        Messages.sendMultiline(plugin, player, listOf(
            "§4§l========[Man10Bank]========",
            "   §7§l債務者:  $borrowerName",
            "   §8§l有効日:  $dateText",
            "   §7§l支払額:  $amtText 円",
            "§4§l=========================="
        ).joinToString("\n"))

        val coll = pdc.get(debtCollateralKey, PersistentDataType.STRING)
        if (!coll.isNullOrBlank()) {
            val items = ItemStackBase64.decodeItems(coll)
            if (items.isNotEmpty()) {
                CollateralViewUI(player, items).open()
            }
        }
    }
}
