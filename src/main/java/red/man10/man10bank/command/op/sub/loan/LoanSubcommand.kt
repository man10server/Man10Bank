package red.man10.man10bank.command.op.sub.loan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.LoanService
import red.man10.man10bank.util.Messages

/**
 * /bankop loan ... - プレイヤーローンの運営操作。
 * - note <loanId>: 最新のローン情報で手形を作り直して実行者に渡す（貸し手に手形が渡らなかった場合など）
 */
class LoanSubcommand(
    private val scope: CoroutineScope,
    private val loanService: LoanService,
) : BankOpSubcommand {
    override val name: String = "loan"
    override val usage: String = "/bankop loan note <loanId> - 手形を再発行して受け取る"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        when (args.getOrNull(1)?.lowercase()) {
            "note" -> handleNote(sender, args)
            else -> Messages.error(sender, "使い方: $usage")
        }
        return true
    }

    private fun handleNote(sender: CommandSender, args: List<String>) {
        if (sender !is Player) {
            Messages.error(sender, "手形を受け取るため、ゲーム内から実行してください。")
            return
        }
        val id = args.getOrNull(2)?.toIntOrNull()
        if (id == null || id <= 0) {
            Messages.error(sender, "ローンIDを指定してください。例: /bankop loan note 12")
            return
        }
        scope.launch { loanService.reissueNote(sender, id) }
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> =
        if (args.size <= 1) listOf("note") else emptyList()
}
