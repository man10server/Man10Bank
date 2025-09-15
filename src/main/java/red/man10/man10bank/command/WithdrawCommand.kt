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
import red.man10.man10bank.api.model.request.DepositRequest
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
                Messages.error(plugin, sender, "金額が不正です。正の数または all を指定してください。")
                return@launch
            }

            // 銀行から出金
            val result = bank.withdraw(withdrawRequest(sender, amount))
            if (result.isSuccess) {
                val newBank = result.getOrNull() ?: 0.0
                // Vault に入金
                val ok = vault.deposit(sender, amount)
                if (ok) {
                    Messages.send(plugin, sender, "出金に成功しました。金額: $amount 銀行残高: $newBank 電子マネー: ${vault.getBalance(sender)}")
                } else {
                    // Vault への入金に失敗したら銀行に返金
                    Messages.error(plugin, sender, "出金は成功しましたが、Vaultへの反映に失敗しました。銀行に返金します")

                    val refundResult = bank.deposit(refundRequest(sender, amount))
                    if (refundResult.isSuccess) {
                        Messages.send(plugin, sender, "返金に成功しました。銀行残高: ${refundResult.getOrNull() ?: 0.0}")
                    } else {
                        Messages.error(plugin, sender, "${amount}円の返金に失敗しました。至急管理者に連絡してください！")
                    }
                }
            } else {
                val ex = result.exceptionOrNull()
                if (ex is InsufficientBalanceException) {
                    // 銀行残高不足は特別扱い
                    Messages.error(plugin, sender, "銀行残高が不足しています。")
                    return@launch
                } else {
                    Messages.error(plugin, sender, "出金に失敗しました: ${ex?.message?:"不明なエラー"}")
                }
            }
        }
        return true
    }

    private fun withdrawRequest(sender: Player, amount: Double): WithdrawRequest {
        return WithdrawRequest(
            uuid = sender.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = "PlayerWithdrawOnCommand",
            displayNote = "/withdrawによる出金",
            server = plugin.serverName
        )
    }

    private fun refundRequest(sender: Player, amount: Double): DepositRequest {
        return DepositRequest(
            uuid = sender.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = "RefundForFailedVaultDeposit",
            displayNote = "Vaultへの反映失敗による返金",
            server = plugin.serverName
        )
    }
}
