package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.Messages

/** /deposit [金額|all] : Vault -> Bank （未指定は全額） */
class DepositCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val bankService: BankService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size > 1) {
            Messages.warn(sender, "使い方: /deposit [金額|all]")
            return true
        }
        val arg = args.getOrNull(0)
        scope.launch {
            val amount = bankService.resolveDepositAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数、または引数なし/ALLで全額を指定してください。")
                return@launch
            }
            bankService.deposit(sender, amount)
        }
        return true
    }
}
