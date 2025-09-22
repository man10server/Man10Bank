package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.Messages

/**
 * /ballog [page] : 銀行取引ログのページ表示
 */
class BalLogCommand(
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
            Messages.warn(sender, "使い方: /ballog [ページ]")
            return true
        }
        val page = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val pageSize = 10
        val offset = page * pageSize

        scope.launch {
            val logs = bankService.getLogs(sender, limit = pageSize, offset = offset) ?: return@launch
            val lines = logs.map { log ->
                val date = log.date?.let { DateFormats.toDateTime(it) } ?: "-"
                val isDeposit = (log.deposit == true)
                val type = if (isDeposit) "§a§l入金" else "§c§l出金"
                val amount = BalanceFormats.colored(log.amount ?: 0.0)
                val note = log.displayNote
                "§7${date} §b${type} §f${amount} §7- $note"
            }
            showPaged(sender, lines, page, "ballog")
        }
        return true
    }
}

