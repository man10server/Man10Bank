package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.api.model.request.AtmLogRequest
import red.man10.man10bank.api.model.response.AtmLog
import red.man10.man10bank.util.errorMessage
import kotlin.math.floor
import java.util.UUID

/**
 * ATM関連のサービス層。
 * - API呼び出しの集約と、現金↔Vaultの交換処理を提供します。
 */
class AtmService(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val api: AtmApiClient,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
) {
    /**
     * ATMログの取得。
     * - 成功時: ログのリストを返す
     * - 失敗時: 例外をスロー（ApiHttpException 等）
     */
    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): List<AtmLog> {
        val result = api.getLogs(uuid, limit, offset)
        return result.getOrElse { throw it }
    }

    /**
     * 現金アイテムをVaultへ換金し、入金額を返す（DESIGN 3.4）。
     * - 先に金額を集計するが、stack.amount を 0 にするのは vault.deposit 成功を確認した後にする。
     * - 入金失敗時はアイテムを一切消費せず（amount を変更しない）現金を手元に残す。
     */
    fun depositCashToVault(player: Player, stacks: Array<ItemStack>): Double {
        if (!vault.isAvailable()) return 0.0

        val target = plugin.server.getOfflinePlayer(player.uniqueId)

        // 入金対象の現金スタックと合計額を先に集計（この時点ではアイテムを消費しない）。
        val cashStacks = mutableListOf<ItemStack>()
        var total = 0.0
        for (stack in stacks) {
            if (stack.amount <= 0 || stack.type.isAir) continue
            val amountPerItem = cashItemManager.getAmountForItem(stack) ?: continue
            total += amountPerItem * stack.amount
            cashStacks.add(stack)
        }
        if (total <= 0) return 0.0
        total = floor(total)

        // 先に Vault へ入金し、成功を確認してから現金アイテムを消費する。
        val success = vault.deposit(target, total)
        if (!success) {
            // 入金失敗。アイテムは未消費のため現金は手元に残る（消失しない）。
            plugin.logger.severe(
                "補償不要[ATM入金失敗] uuid=${player.uniqueId} 金額=${total} 操作=Vault入金 " +
                        "note=ATMDeposit 詳細=電子マネー入金に失敗したため現金は消費せず手元に残した"
            )
            return 0.0
        }

        // 入金成功を確認できたので現金アイテムを消費する。
        for (stack in cashStacks) {
            stack.amount = 0
        }
        logAtmAsync(player, total, true)
        return total
    }

    /**
     * Vault残高を現金アイテムへ変換してプレイヤーへ付与する（DESIGN 3.4）。
     * - 先に現金アイテムを生成し、付与可能であることを確認してから vault.withdraw する。
     * - addItem で入りきらなかった分があれば、その差額を即時 vault.deposit で返金する。
     * @return 付与に成功して引き出せた金額（0.0 の場合は引き出し不成立）。
     */
    fun withdrawVaultToCash(player: Player, amount: Double): Double {
        if (!vault.isAvailable()) return 0.0
        if (amount <= 0.0) return 0.0

        // 1) アイテム生成（未登録金種なら null）。生成できなければ Vault には一切触れない。
        val item = cashItemManager.getItemForAmount(amount) ?: return 0.0

        // 2) 残高確認込みの出金（不足時は false）。アイテム付与前に出金しておき、
        //    付与に失敗した分は直後に返金して整合を保つ。
        val withdrew = vault.withdraw(player, amount)
        if (!withdrew) return 0.0

        // 3) インベントリへ付与。入りきらなかった分（leftovers）は金額に換算して即時返金する。
        val leftovers = player.inventory.addItem(item)
        if (leftovers.isNotEmpty()) {
            var refundAmount = 0.0
            for (left in leftovers.values) {
                val unit = cashItemManager.getAmountForItem(left) ?: continue
                refundAmount += unit * left.amount
            }
            if (refundAmount > 0.0) {
                val refunded = vault.deposit(player, refundAmount)
                if (!refunded) {
                    // 返金にも失敗。Vault が減ったまま現金も渡らない不整合のため構造化ログを残す（DESIGN 3.4/3.6）。
                    plugin.logger.severe(
                        "補償失敗[ATM出金返金] uuid=${player.uniqueId} 金額=${refundAmount} 操作=Vault返金 " +
                                "note=ATMWithdraw 詳細=現金アイテムがインベントリに入りきらず返金にも失敗"
                    )
                }
            }
            val granted = amount - refundAmount
            if (granted > 0.0) logAtmAsync(player, granted, false)
            return if (granted > 0.0) granted else 0.0
        }

        // 全量付与成功
        logAtmAsync(player, amount, false)
        return amount
    }

    private fun logAtmAsync(player: Player, amount: Double, deposit: Boolean) {
        scope.launch(Dispatchers.IO) {
            val req = AtmLogRequest(
                uuid = player.uniqueId.toString(),
                amount = amount,
                deposit = deposit,
            )
            val result = api.appendLog(req)
            if (result.isFailure) {
                plugin.logger.warning("ATMログ送信に失敗しました: ${result.errorMessage()}")
            }
        }
    }
}
