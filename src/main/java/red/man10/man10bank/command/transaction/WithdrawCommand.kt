package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.InsufficientBalanceException
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.BalanceFormats

/** /withdraw <金額|all> : Bank -> Vault */
class WithdrawCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vault: VaultManager,
    private val bank: BankApiClient,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    private suspend fun resolveAmount(player: Player, arg: String): Double? {
        // all の場合はAPIで銀行残高を取得
        val amount: Double = if (arg.equals("all", ignoreCase = true)) {
            bank.getBalance(player.uniqueId).getOrElse { 0.0 }
        } else arg.toDoubleOrNull() ?: -1.0
        return if (amount > 0.0) amount else null
    }

    private suspend fun process(player: Player, amount: Double) {
        // 銀行から出金
        val result = bank.withdraw(withdrawRequest(player, amount))

        // APIエラー処理
        if (!result.isSuccess) {
            val ex = result.exceptionOrNull()
            if (ex is InsufficientBalanceException) {
                // 銀行残高不足は特別扱い
                Messages.error(plugin, player, "銀行残高が不足しています。")
            } else {
                Messages.error(plugin, player, "出金に失敗しました: ${ex?.message?:"不明なエラー"}")
            }
            return
        }

        val newBank = result.getOrNull() ?: 0.0

        // Vault に入金
        val ok = vault.deposit(player, amount)
        if (ok) {
            Messages.send(
                plugin,
                player,
                "出金に成功しました。" +
                        "金額: ${BalanceFormats.colored(amount)} " +
                        "銀行残高: ${BalanceFormats.colored(newBank)} " +
                        "電子マネー: ${BalanceFormats.colored(vault.getBalance(player))}"
            )
            return
        }

        // Vault への入金に失敗したら銀行に返金
        Messages.error(plugin, player, "出金は成功しましたが、Vaultへの反映に失敗しました。銀行に返金します")
        val refundResult = bank.deposit(refundRequest(player, amount))
        if (refundResult.isSuccess) {
            Messages.send(plugin, player, "返金に成功しました。銀行残高: ${BalanceFormats.colored(refundResult.getOrNull() ?: 0.0)}")
        } else {
            Messages.error(plugin, player, "${BalanceFormats.colored(amount)}円の返金に失敗しました。至急管理者に連絡してください！")
        }

    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 1) {
            Messages.warn(sender, "使い方: /withdraw <金額/all>")
            return true
        }
        val arg = args[0]
        scope.launch {
            val amount = resolveAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数または all を指定してください。")
                return@launch
            }
            process(sender, amount)
        }
        return true
    }

    private fun withdrawRequest(sender: Player, amount: Double): WithdrawRequest =
        WithdrawRequest(
            uuid = sender.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = "PlayerWithdrawOnCommand",
            displayNote = "/withdrawによる出金",
            server = plugin.serverName
        )

    private fun refundRequest(sender: Player, amount: Double): DepositRequest =
        DepositRequest(
            uuid = sender.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = "RefundForFailedVaultDeposit",
            displayNote = "Vaultへの反映失敗による返金",
            server = plugin.serverName
        )
}
