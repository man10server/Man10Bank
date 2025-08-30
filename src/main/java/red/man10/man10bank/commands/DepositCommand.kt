package red.man10.man10bank.commands

import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.model.ResultCode
import red.man10.man10bank.util.StringFormat
import red.man10.man10bank.util.BigDecimalConverter

class DepositCommand(private val plugin: Man10Bank) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("プレイヤーのみ実行できます。")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("使用方法: /deposit <金額>")
            return true
        }
        val amount = BigDecimalConverter.parseOrNull(args[0])
        if (amount == null || amount.signum() <= 0) {
            sender.sendMessage("金額は正の数で指定してください。")
            return true
        }
        val uuid = sender.uniqueId
        plugin.appScope.launch {
            val vaultRes = plugin.vault.withdraw(uuid, amount)
            if (vaultRes.code != ResultCode.SUCCESS) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage(vaultRes.code.message)
                })
                return@launch
            }

            val bankRes = plugin.bankService.deposit(uuid, amount, "PlayerDepositOnCommand", "/depositによる入金")
            if (bankRes.code == ResultCode.SUCCESS) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage("所持金から銀行に入金しました。残高: ${StringFormat.money(bankRes.balance!!)}")
                })
            } else {
                // 返金
                plugin.vault.deposit(uuid, amount)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage(bankRes.code.message)
                    sender.sendMessage("入金に失敗したため、所持金に返金しました。")
                })
            }
        }
        return true
    }
}
