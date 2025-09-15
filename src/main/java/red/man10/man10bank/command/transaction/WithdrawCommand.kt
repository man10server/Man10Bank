package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
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
    plugin: Man10Bank,
    scope: CoroutineScope,
    vault: VaultManager,
    bank: BankApiClient,
) : TransactionCommand(plugin, scope, vault, bank) {

    override val usage: String = "/withdraw <金額/all>"

    override suspend fun resolveAmount(player: Player, arg: String): Double? {
        // all の場合はAPIで銀行残高を取得
        val amount: Double = if (arg.equals("all", ignoreCase = true)) {
            bank.getBalance(player.uniqueId).getOrElse { 0.0 }
        } else arg.toDoubleOrNull() ?: -1.0
        return if (amount > 0.0) amount else null
    }

    override suspend fun process(player: Player, amount: Double) {
        // 銀行から出金
        val result = bank.withdraw(withdrawRequest(player, amount))
        if (result.isSuccess) {
            val newBank = result.getOrNull() ?: 0.0
            // Vault に入金
            val ok = vault.deposit(player, amount)
            if (ok) {
                Messages.send(plugin, player, "出金に成功しました。金額: $amount 銀行残高: $newBank 電子マネー: ${vault.getBalance(player)}")
            } else {
                // Vault への入金に失敗したら銀行に返金
                Messages.error(plugin, player, "出金は成功しましたが、Vaultへの反映に失敗しました。銀行に返金します")

                val refundResult = bank.deposit(refundRequest(player, amount))
                if (refundResult.isSuccess) {
                    Messages.send(plugin, player, "返金に成功しました。銀行残高: ${refundResult.getOrNull() ?: 0.0}")
                } else {
                    Messages.error(plugin, player, "${amount}円の返金に失敗しました。至急管理者に連絡してください！")
                }
            }
        } else {
            val ex = result.exceptionOrNull()
            if (ex is InsufficientBalanceException) {
                // 銀行残高不足は特別扱い
                Messages.error(plugin, player, "銀行残高が不足しています。")
            } else {
                Messages.error(plugin, player, "出金に失敗しました: ${ex?.message?:"不明なエラー"}")
            }
        }
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
