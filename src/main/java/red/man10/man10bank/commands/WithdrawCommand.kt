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

class WithdrawCommand(private val plugin: Man10Bank) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("プレイヤーのみ実行できます。")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("使用方法: /withdraw <金額>")
            return true
        }
        val amount = args[0].toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) {
            sender.sendMessage("金額は正の数で指定してください。")
            return true
        }
        val uuid = sender.uniqueId
        plugin.appScope.launch {
            val bankRes = plugin.bankService.withdraw(uuid, amount, "PlayerWithdrawOnCommand", "/withdrawによる出金")
            if (bankRes.code != ResultCode.SUCCESS) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage(bankRes.code.message)
                })
                return@launch
            }

            val vaultRes = plugin.vault.deposit(uuid, amount)
            if (vaultRes.code == ResultCode.SUCCESS) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage("銀行から所持金に出金しました。銀行残高: ${StringFormat.money(bankRes.balance!!)}")
                })
            } else {
                // 銀行へ戻す（補償）
                plugin.bankService.deposit(uuid, amount, "RollbackVaultFailure", "Vault入金失敗の返金")
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    sender.sendMessage(vaultRes.code.message)
                    sender.sendMessage("所持金への入金に失敗したため、銀行残高を元に戻しました。")
                })
            }
        }
        return true
    }
}
