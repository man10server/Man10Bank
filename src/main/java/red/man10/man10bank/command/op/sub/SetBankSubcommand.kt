package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import red.man10.man10bank.service.BankService

/**
 * /bankop editbank <player> <金額> <理由> - 指定プレイヤーの銀行残高を調整
 */
class SetBankSubcommand(
    private val scope: CoroutineScope,
    private val bankService: BankService,
) : EditBalanceSubcommand(
    name = "editbank",
    usageDescription = "銀行残高を指定額に調整",
) {
    override fun executeEdit(sender: CommandSender, target: EditTarget) {
        scope.launch {
            bankService.setBalance(
                sender = sender,
                targetUuid = target.offline.uniqueId,
                targetName = target.displayName,
                amount = target.amount,
                reason = target.reason,
            )
        }
    }
}
