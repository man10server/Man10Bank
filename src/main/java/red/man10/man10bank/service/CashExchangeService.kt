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
            total += amountPerItem * stack.amount
            stack.amount = 0
        }
        if (total <= 0) return 0.0
        total = floor(total)

        val success = vault.deposit(target, total)
        return if (success) total else 0.0
    }

    /** Vault残高を現金アイテムへ変換し、作成したアイテムを返す。 */
    fun withdrawVaultToCash(player: Player, amount: Double): ItemStack? {
        if (!vault.isAvailable()) return null
        if (amount <= 0.0) return null

        val success = vault.withdraw(player, amount)
        return if (success) cashItemManager.getItemForAmount(amount) else null
    }
}
