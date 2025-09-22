package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.BalanceFormats

/** /deposit <金額|all> : Vault -> Bank */
class DepositCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vault: VaultManager,
    private val bankService: BankService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 1) {
            Messages.warn(sender, "使い方: /deposit <金額/all>")
            return true
        }
        val arg = args[0]
        scope.launch {
            val amount = resolveAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数または all を指定してください。")
                return@launch
            }
            bankService.deposit(sender, amount)
        }
        return true
    }

    private fun resolveAmount(player: Player, arg: String): Double? {
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "Vaultが利用できません。")
            return null
        }
        val vaultBal = vault.getBalance(player)
        val amount = if (arg.equals("all", ignoreCase = true)) vaultBal else arg.toDoubleOrNull() ?: -1.0
        if (amount > vaultBal) {
            Messages.error(plugin, player, "所持金が不足しています。" +
                    "保有: ${BalanceFormats.colored(vaultBal)} " +
                    "§c§l要求: ${BalanceFormats.colored(amount)}")
            return null
        }
        return if (amount > 0.0) amount else null
    }

    // processはBankServiceへ移行
}
