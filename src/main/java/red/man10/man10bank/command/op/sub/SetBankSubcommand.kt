package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.Messages

/**
 * /bankop setbank <player> <金額> <理由> - 指定プレイヤーの銀行残高を調整
 */
class SetBankSubcommand(
    private val scope: CoroutineScope,
    private val bankService: BankService,
) : BankOpSubcommand {
    override val name: String = "setbank"
    override val usage: String = "/bankop setbank <player> <金額> <理由> - 銀行残高を指定額に調整"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 4) {
            Messages.error(sender, "使い方: /bankop setbank <player> <金額> <理由>")
            return true
        }
        val targetName = args[1]
        val amount = args[2].toDoubleOrNull()
        if (amount == null) {
            Messages.error(sender, "金額は数値で指定してください。")
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            Messages.error(sender, "理由を指定してください。")
            return true
        }

        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId
        val displayName = offline.name ?: targetName

        scope.launch {
            bankService.setBalance(sender, uuid, displayName, amount, reason)
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.sorted()
        }
        return emptyList()
    }
}
