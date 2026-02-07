package red.man10.man10bank.command.op.sub.edit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage

/**
 * /bankop editserverloan <player> <金額> <理由> - 指定プレイヤーのサーバーローン借入額を調整
 */
class EditServerLoanSubCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val serverLoanService: ServerLoanService,
) : EditBalanceSubcommand(
    name = "editserverloan",
    usageDescription = "サーバーローン借入額を指定額に調整",
) {
    override fun executeEdit(sender: CommandSender, target: EditTarget) {
        scope.launch {
            val result = serverLoanService.setBorrowAmount(target.offline.uniqueId, target.amount)
            if (result.isSuccess) {
                val requested = target.amount.toLong().toDouble()
                val updatedBorrowAmount = result.getOrNull()?.borrowAmount ?: requested
                Messages.send(
                    plugin,
                    sender,
                    "サーバーローン借入額を調整しました。対象: ${target.displayName} " +
                        "変更後: ${BalanceFormats.coloredYen(updatedBorrowAmount)} 理由: ${target.reason}"
                )
            } else {
                val msg = result.errorMessage()
                Messages.error(plugin, sender, "サーバーローン借入額の調整に失敗しました: $msg")
            }
        }
    }
}
