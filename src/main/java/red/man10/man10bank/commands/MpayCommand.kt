package red.man10.man10bank.commands

import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.model.ResultCode
import red.man10.man10bank.util.StringFormat
import java.math.BigDecimal

class MpayCommand(private val plugin: Man10Bank) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("プレイヤーのみ実行できます。")
            return true
        }
        if (args.size < 2) {
            sender.sendMessage("使用方法: /mpay <相手> <金額>")
            return true
        }
        val targetName = args[0]
        val amount = args[1].toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) {
            sender.sendMessage("金額は正の数で指定してください。")
            return true
        }
        val target: OfflinePlayer = Bukkit.getOfflinePlayer(targetName)
        plugin.appScope.launch {
            val res = plugin.bankService.transfer(sender.uniqueId, target.uniqueId, amount)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (res.code == ResultCode.SUCCESS) {
                    sender.sendMessage("$targetName へ ${StringFormat.money(amount)} を送金しました。残高: ${StringFormat.money(res.balance!!)}")
                } else {
                    sender.sendMessage(res.code.message)
                }
            })
        }
        return true
    }
}
