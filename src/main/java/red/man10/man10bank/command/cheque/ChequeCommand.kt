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

/** /mcheque <金額> <メモ> - 小切手発行（/mchequeop で運営用） */
class ChequeCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val chequeService: ChequeService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {
    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        // ラベルで運営用か判定（/mchequeop は運営用）
        val isOpLabel = label.equals("mchequeop", ignoreCase = true)

        // 運営用ラベルで実行した場合はOP権限を要求
        if (isOpLabel && !sender.isOp) {
            Messages.error(sender, "このコマンドは管理者のみ実行できます。")
            return true
        }

        if (args.isEmpty()) {
            Messages.warn(sender, "使い方: /$label <金額> <メモ>")
            return true
        }
        val amount = args[0].toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }
        val note = args.getOrNull(1)
        if (note != null && note.length >= 20) {
            Messages.error(sender, "メモは20文字未満で指定してください。")
            return true
        }
        scope.launch {
            val item = chequeService.createCheque(sender, amount, note, isOP = isOpLabel)
            if (item == null) {
                Messages.error(plugin, sender, "小切手の発行に失敗しました。")
                return@launch
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                val remains = sender.inventory.addItem(item)
                if (remains.isNotEmpty()) {
                    remains.values.forEach { sender.world.dropItemNaturally(sender.location, it) }
                }
                val msg = (if (isOpLabel) "運営小切手を発行しました。" else "小切手を発行しました。") + " 金額: ${BalanceFormats.colored(amount)}"
                Messages.send(plugin, sender, msg)
            })
        }
        return true
    }
}
