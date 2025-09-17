package red.man10.man10bank.service

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * 現金とVault残高の交換を担当するサービス。
 */
class CashExchangeService(
    private val plugin: JavaPlugin,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
) {

    //TODO: ログ記録

    /** 現金アイテムをVaultへ換金し、入金額を返す。 */
    fun depositCashToVault(player: Player, stacks: Array<ItemStack>): Double {
        if (!vault.isAvailable()) return 0.0

        val target = plugin.server.getOfflinePlayer(player.uniqueId)
        var total = 0.0

        for (stack in stacks) {
            if (stack.amount <= 0 || stack.type.isAir) continue
            val amountPerItem = cashItemManager.getAmountForItem(stack) ?: continue
            total = amountPerItem * stack.amount
            stack.amount = 0
        }
        if (total <= 0) return 0.0
        total = floor(total)

        val success = vault.deposit(target, total)
        return if (success) total else 0.0
    }

    /** Vault残高を現金アイテムへ変換し、作成したアイテムを返す。 */
    fun withdrawVaultToCash(player: Player, amount: Double): List<ItemStack> {
        if (!vault.isAvailable()) return emptyList()
        if (amount <= 0.0) return emptyList()

        val target = plugin.server.getOfflinePlayer(player.uniqueId)
        if (vault.getBalance(target) < amount) {
            return emptyList()
        }
        val denominations = cashItemManager.getRegisteredCashItems()
        if (denominations.isEmpty()) return emptyList()
        val sorted = denominations.entries.sortedByDescending { it.key }

        var remaining = floor(amount)
        val withdrawCash = mutableListOf<ItemStack>()

        for ((value, item) in sorted) {
            val maxCount = floor(remaining / value).toInt()
            if (maxCount <= 0) continue
            val takeAmount = value * maxCount
            remaining -= takeAmount
            val cashStack = item.clone()
            cashStack.amount = maxCount
            withdrawCash += cashStack
            if (remaining <= 0) throw Exception("${amount}以上の現金を作成しました")
        }

        val withdrawAmount = amount - remaining
        val success = vault.withdraw(target, withdrawAmount)
        return if (success) return withdrawCash else emptyList()
    }
}
