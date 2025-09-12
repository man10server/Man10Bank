package red.man10.man10bank.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.InsufficientBalanceException
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages

/** /withdraw <金額|all> : Bank -> Vault */
class WithdrawCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vault: VaultManager,
    private val bank: BankApiClient,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("man10bank.user")) {
            Messages.error(sender, "このコマンドを実行する権限がありません。")
            return true
        }
        if (sender !is Player) {
            Messages.error(sender, "このコマンドはプレイヤーのみ使用できます。")
            return true
        }
        if (args.size != 1) {
            Messages.warn(sender, "使い方: /withdraw <金額/all>")
            return true
        }
        val arg = args[0]

        scope.launch {
            // all の場合はAPIで銀行残高を取得
            val amount: Double = if (arg.equals("all", ignoreCase = true)) {
                bank.getBalance(sender.uniqueId).getOrElse { 0.0 }
            } else arg.toDoubleOrNull() ?: -1.0

            if (amount <= 0.0) {
                plugin.server.scheduler.runTask(plugin, Runnable { Messages.error(sender, "金額が不正です。正の数または all を指定してください。") })
                return@launch
            }

            val serverName = plugin.config.getString("serverName", "") ?: ""
            val req = WithdrawRequest(
                uuid = sender.uniqueId.toString(),
                amount = amount,
                pluginName = plugin.name,
                note = "Vaultへ出金",
                displayNote = "出金: $amount",
                server = serverName
            )

            val result = bank.withdraw(req)
            if (result.isSuccess) {
                val newBank = result.getOrNull() ?: 0.0
                // Vault に入金
                val ok = vault.deposit(sender, amount)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (ok) {
                        Messages.send(sender, "出金に成功しました。金額: $amount 銀行残高: $newBank 所持金: ${vault.getBalance(sender)}")
                    } else {
                        Messages.warn(sender, "出金は成功しましたが、Vaultへの反映に失敗しました。管理者へ連絡してください。")
                    }
                })
            } else {
                val ex = result.exceptionOrNull()
                val msg = when (ex) {
                    is InsufficientBalanceException -> "銀行残高が不足しています。"
                    else -> ex?.message ?: "不明なエラー"
                }
                plugin.server.scheduler.runTask(plugin, Runnable { Messages.error(sender, "出金に失敗しました: $msg") })
            }
        }
        return true
    }
}
