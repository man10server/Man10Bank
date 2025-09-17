package red.man10.man10bank.command.cheque

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.ChequeService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/** /mchequeop <金額> [メモ] - 運営用小切手発行 */
class ChequeOpCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val chequeService: ChequeService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = false,
) {
    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.isEmpty()) {
            Messages.warn(sender, "使い方: /mchequeop <金額> [メモ]")
            return true
        }
        val amount = args[0].toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }
        val note = args.drop(1).joinToString(" ").ifBlank { null }
        if (note != null && note.length >= 20) {
            Messages.error(sender, "メモは20文字未満で指定してください。")
            return true
        }
        scope.launch {
            val item = chequeService.createCheque(sender, amount, note, isOP = true)
            if (item == null) {
                Messages.error(plugin, sender, "小切手の発行に失敗しました。")
                return@launch
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                val remains = sender.inventory.addItem(item)
                if (remains.isNotEmpty()) {
                    remains.values.forEach { sender.world.dropItemNaturally(sender.location, it) }
                }
                Messages.send(plugin, sender, "運営小切手を発行しました。金額: ${BalanceFormats.colored(amount)}")
            })
        }
        return true
    }
}
