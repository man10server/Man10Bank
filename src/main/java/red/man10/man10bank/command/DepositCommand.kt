package red.man10.man10bank.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages

/** /deposit <金額|all> : Vault -> Bank */
class DepositCommand(
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
            Messages.warn(sender, "使い方: /deposit <金額/all>")
            return true
        }
        val arg = args[0]
        val vaultBal = vault.getBalance(sender)
        val amount = if (arg.equals("all", ignoreCase = true)) vaultBal else arg.toDoubleOrNull() ?: -1.0
        if (amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数または all を指定してください。")
            return true
        }
        if (amount > vaultBal) {
            Messages.error(sender, "所持金が不足しています。保有: $vaultBal 要求: $amount")
            return true
        }
        if (!vault.isAvailable()) {
            Messages.error(sender, "Vaultが利用できません。")
            return true
        }

        scope.launch {
            // 1) Vault から引き落とし
            val withdrew = vault.withdraw(sender, amount)
            if (!withdrew) {
                plugin.server.scheduler.runTask(plugin, Runnable { Messages.error(sender, "Vaultからの引き落としに失敗しました。") })
                return@launch
            }
            // 2) Bank へ入金
            val req = DepositRequest(
                uuid = sender.uniqueId.toString(),
                amount = amount,
                pluginName = plugin.name,
                note = "Vaultから入金",
                displayNote = "入金: $amount",
                server = plugin.serverName
            )
            val result = bank.deposit(req)
            if (result.isSuccess) {
                val newBank = result.getOrNull() ?: 0.0
                plugin.server.scheduler.runTask(plugin, Runnable {
                    Messages.send(sender, "入金に成功しました。金額: $amount 銀行残高: $newBank 所持金: ${vault.getBalance(sender)}")
                })
            } else {
                // 失敗したので Vault に返金
                vault.deposit(sender, amount)
                val msg = result.exceptionOrNull()?.message ?: "不明なエラー"
                plugin.server.scheduler.runTask(plugin, Runnable { Messages.error(sender, "入金に失敗しました: $msg") })
            }
        }
        return true
    }
}
