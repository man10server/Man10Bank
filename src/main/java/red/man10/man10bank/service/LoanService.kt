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
import org.bukkit.event.block.Action.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.api.isIndeterminateFailure
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
    private val oldIdKey = NamespacedKey(plugin, "id")

    /** [create] の結果。 */
    enum class CreateResult {
        /** 作成された（貸し手へ手形を発行済み）。 */
        CREATED,
        /** 作成されなかったことが確定している。担保は借り手へ返してよい。 */
        FAILED,
        /** タイムアウト等でサーバーの応答を確認できず、作成されたか分からない。担保を返すと二重になり得るため返さないこと。 */
        INDETERMINATE,
    }

    /**
     * ローン作成。
     * - 結果を [CreateResult] で返す（メッセージ表示や手形発行などの副作用は本関数内で処理）
     * - collateral は任意（null 可）
     */
    suspend fun create(
        lender: Player,
        borrower: Player,
        borrowAmount: Double,
        repayAmount: Double,
        paybackInDays: Int,
        collaterals: List<ItemStack>?,
    ): CreateResult {
        if (borrowAmount <= 0) {
            Messages.error(plugin, lender, "借入金額が不正です。0より大きい値を指定してください。")
            return CreateResult.FAILED
        }
        if (repayAmount <= 0.0) {
            Messages.error(plugin, lender, "返済金額が不正です。0より大きい値を指定してください。")
            return CreateResult.FAILED
        }
        if (paybackInDays <= 0) {
            Messages.error(plugin, lender, "返済期限日数が不正です。1以上を指定してください。")
            return CreateResult.FAILED
        }

        val paybackDateIso = OffsetDateTime.now(ZoneOffset.UTC).plusDays(paybackInDays.toLong()).toString()
        val filtered = collaterals?.filter { !it.type.isAir }.orEmpty()
        val encoded = if (filtered.isEmpty()) {
            null
        } else {
            encodeCollateralItems(lender, borrower, filtered) ?: return CreateResult.FAILED
        }

        val body = LoanCreateRequest(
            lendUuid = lender.uniqueId.toString(),
            borrowUuid = borrower.uniqueId.toString(),
            borrowAmount = borrowAmount,
            repayAmount = repayAmount,
            paybackDate = paybackDateIso,
            collateralItem = encoded,
        )
        val result = api.create(body)

        val loan = result.getOrNull()
        if (loan == null) {
            val cause = result.exceptionOrNull()
            if (cause == null || isIndeterminateFailure(cause)) {
                // サーバーが作成済み（担保もDBに記録済み）の可能性がある。ここで担保を借り手へ返すと、
                // 作成済みだった場合に返還・回収でもう一度担保が出てきて二重になるため、返さず運営の確認に回す。
                plugin.logger.severe(
                    "要手動対応[ローン作成結果不明] lender=${lender.uniqueId} borrower=${borrower.uniqueId} " +
                        "借入金額=${borrowAmount} 返済金額=${repayAmount} 返済期限=${paybackDateIso} 担保件数=${filtered.size} " +
                        "詳細=サーバーの応答を確認できなかったため担保を返却せず保留した。サーバーDBの loan で作成有無を確認し、" +
                        "作成済みなら貸し手へ手形を渡し、未作成なら借り手へ担保を返却すること 原因=${cause?.message} " +
                        "担保データ(Base64)=${encoded ?: "なし"}"
                )
                val collateralNote = if (filtered.isEmpty()) "" else "担保は運営の確認が終わるまで保留されます。"
                Messages.error(plugin, lender, "ローン作成の結果をサーバーに確認できませんでした。作成されている可能性があるため、運営に連絡してください。")
                Messages.error(plugin, borrower, "借入の結果をサーバーに確認できませんでした。${collateralNote}運営に連絡してください。")
                return CreateResult.INDETERMINATE
            }
            val msg = result.errorMessage()
            Messages.error(plugin, lender, "ローン作成に失敗しました: $msg")
            Messages.error(plugin, borrower, "借入が確定できませんでした。$msg")
            return CreateResult.FAILED
        }

        // 債権者に手形を発行し、双方に通知
        val note = issueDebtNote(loan)
        plugin.server.scheduler.runTask(plugin, Runnable {
            val leftover = lender.inventory.addItem(note)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { lender.world.dropItemNaturally(lender.location, it) }
            }
            Messages.send(plugin, lender, "ローン手形を発行しました。")
            Messages.send(plugin, borrower, "借入が確定しました。金額: ${BalanceFormats.coloredYen(borrowAmount)}円")
        })
        return CreateResult.CREATED
    }

    /**
     * 借り手のローン取得（プレイヤー単位）。
     * - APIの成功/失敗はサービス層で処理し、失敗時は空リストを返す。
     * - エラー時はプレイヤーに日本語で通知。
     */
    suspend fun getBorrowerLoans(player: Player, limit: Int = 100, offset: Int = 0): List<Loan> {
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
        val items = decodeCollateralItems(borrower, id, base64, "borrower") ?: return
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

        // ローン機能が停止中の場合は手形の使用も禁止
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.LOAN)) {
            event.isCancelled = true
            Messages.error(plugin, player, "プレイヤーローン機能は現在停止中です。")
            return
        }

        // 右クリックのみを対象
        val action = event.action
        val isRightClick = when (action) {
            RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> true
            else -> false
        }
        if (!isRightClick) return

        // 手形の使用を処理
        event.isCancelled = true
        // 使った手形はこの時点で手から1枚取り上げて預かる。以前は応答を待ってから元の手形を消していたため、
        // 待っている間に手形を動かされる(移動・ドロップ等で別のアイテムになる)と消し損ねて手形が複製されていた。
        val note = takeOneNoteFromHand(player, event.hand, id) ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repay(player, id, note)
        }
    }

    /**
     * 手形での返済（回収）。[note] は呼び出し元で手から取り上げ済みの手形。
     * 最新のローン情報で作り直した手形を渡せた場合はそれを、返済されなかった・最新情報を取れなかった場合は
     * 元の手形をそのまま返す（どの経路でも預かった手形が消えないように finally で返す）。
     */
    private suspend fun repay(collector: Player, id: Int, note: ItemStack) {
        var replacement: ItemStack? = null
        var notice: String? = null
        try {
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
                    val items = decodeCollateralItems(collector, id, base64, "collector") ?: return
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

            // 返済後に最新のローン情報で手形を作り直す
            val loanRes = api.get(id)
            if (!loanRes.isSuccess) {
                val msg = loanRes.errorMessage()
                Messages.error(plugin, collector, "最新のローン情報の取得に失敗しました: $msg")
                return
            }
            val loan = loanRes.getOrNull() ?: return
            replacement = issueDebtNote(loan)
            val settled = (loan.amount ?: 0.0) <= 0.0
            notice = if (settled) "返済が完了したため、手形を完済済みにしました。" else "手形を更新しました。"
        } finally {
            handBackNote(collector, id, replacement ?: note, notice)
        }
    }

    /**
     * 手に持っている手形を1枚取り上げて返す。手の手形がこのローンのものでなければ null。
     * メインスレッドで呼ぶこと。
     */
    private fun takeOneNoteFromHand(player: Player, hand: EquipmentSlot?, id: Int): ItemStack? {
        val slot = hand ?: return null
        val inHand = player.inventory.getItem(slot)
        if (getLoanId(inHand) != id) return null
        val taken = inHand.asOne()
        player.inventory.setItem(slot, if (inHand.amount > 1) inHand.clone().apply { amount -= 1 } else null)
        return taken
    }

    /**
     * 預かっていた手形（または作り直した手形）をプレイヤーへ渡す。
     * プラグイン停止中やプレイヤーがログアウト済みで渡せない場合は、運営が復旧できるよう手形データを要確認ログへ残す（DESIGN 3.6）。
     */
    private fun handBackNote(player: Player, loanId: Int, note: ItemStack, notice: String?) {
        if (!plugin.isEnabled) {
            logLostNote(player, loanId, note, "プラグイン停止中のため手形を返せなかった")
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            val online = plugin.server.getPlayer(player.uniqueId)
            if (online == null) {
                logLostNote(player, loanId, note, "プレイヤーがログアウト済みのため手形を返せなかった")
                return@Runnable
            }
            online.inventory.addItem(note).values.forEach { online.world.dropItemNaturally(online.location, it) }
            notice?.let { Messages.send(online, it) }
        })
    }

    private fun logLostNote(player: Player, loanId: Int, note: ItemStack, reason: String) {
        val encoded = runCatching { ItemStackBase64.encode(note) }.getOrElse { "エンコード失敗: ${it.message}" }
        plugin.logger.severe(
            "要手動対応[手形返却失敗] uuid=${player.uniqueId} loanId=${loanId} 詳細=${reason} 手形データ(Base64)=${encoded}"
        )
    }

    private fun issueDebtNote(loan: Loan): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta
        meta.setCustomModelData(2)
        meta.displayName(Component.text("§c§l約束手形 §7§l(Promissory Note)"))

        val borrowerName = Bukkit.getOfflinePlayer(UUID.fromString(loan.borrowUuid)).name
        val paybackText = DateFormats.toDate(loan.paybackDate!!)
        val debt = BalanceFormats.amount(loan.amount?:0.0)
        // 完済（支払額0）の手形は使い道が無いため、使用済み小切手と同様に「完済」と表示して識別キーを付けない
        val settled = (loan.amount ?: 0.0) <= 0.0
        val collateralText = when {
            loan.collateralItem.isNullOrBlank() -> "なし"
            loan.collateralReleased -> "処理済み"
            else -> "あり"
        }
        val lore = mutableListOf(
            Component.text("§4§l========[Man10Bank]========"),
            Component.text("   §7§l債務者:  $borrowerName"),
            Component.text("   §8§l有効日:  $paybackText"),
            Component.text("   §7§l支払額:  $debt 円" ),
            Component.text("   §8§l担保等:  $collateralText"),
        )
        if (settled) lore.add(Component.text("  §c§o[完済]  "))
        lore.add(Component.text("§4§l=========================="))
        meta.lore(lore)
        if (!settled) meta.persistentDataContainer.set(loanIdKey, PersistentDataType.INTEGER, loan.id ?: -1)

        meta.addEnchant(Enchantment.FORTUNE, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)

        item.itemMeta = meta
        return item
    }

    private fun encodeCollateralItems(lender: Player, borrower: Player, items: List<ItemStack>): String? {
        return try {
            ItemStackBase64.encodeItems(items)
        } catch (e: Exception) {
            val itemSummary = items.take(5).joinToString(",") { it.type.name }
            val itemSuffix = if (items.size > 5) "..." else ""
            plugin.logger.warning(
                "担保アイテムのエンコードに失敗しました。loanId=未採番 lender=${lender.uniqueId} " +
                    "borrower=${borrower.uniqueId} count=${items.size} items=$itemSummary$itemSuffix: ${e.message}"
            )
            e.printStackTrace()
            Messages.error(plugin, lender, "担保アイテムの変換に失敗しました。")
            Messages.error(plugin, borrower, "担保アイテムの変換に失敗しました。")
            null
        }
    }

    private fun decodeCollateralItems(player: Player, loanId: Int, base64: String, roleKey: String): List<ItemStack>? {
        return try {
            ItemStackBase64.decodeItems(base64)
        } catch (e: Exception) {
            plugin.logger.warning(
                "担保アイテムのデコードに失敗しました。loanId=$loanId $roleKey=${player.uniqueId} " +
                    "base64Length=${base64.length}: ${e.message}"
            )
            e.printStackTrace()
            Messages.warn(plugin, player, "担保データが不正です。")
            null
        }
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
