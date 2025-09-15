package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages

/** /deposit <金額|all> : Vault -> Bank */
class DepositCommand(
    plugin: Man10Bank,
    scope: CoroutineScope,
    vault: VaultManager,
    bank: BankApiClient,
) : TransactionCommand(plugin, scope, vault, bank) {

    override val usage: String = "/deposit <金額/all>"

    override suspend fun resolveAmount(player: Player, arg: String): Double? {
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "Vaultが利用できません。")
            return null
        }
        val vaultBal = vault.getBalance(player)
        val amount = if (arg.equals("all", ignoreCase = true)) vaultBal else arg.toDoubleOrNull() ?: -1.0
        if (amount <= 0.0) return null
        if (amount > vaultBal) {
            Messages.error(plugin, player, "所持金が不足しています。保有: $vaultBal 要求: $amount")
            return null
        }
        return amount
    }

    override suspend fun process(player: Player, amount: Double) {
        // Vault から引き落とし
        val withdrew = vault.withdraw(player, amount)
        if (!withdrew) {
            Messages.error(plugin, player, "Vaultからの引き落としに失敗しました。")
            return
        }

        // Bank へ入金
        val result = bank.deposit(depositRequest(player, amount))
        if (result.isSuccess) {
            val newBank = result.getOrNull() ?: 0.0
            Messages.send(plugin, player, "入金に成功しました。金額: $amount 銀行残高: $newBank 所持金: ${vault.getBalance(player)}")
        } else {
            // 失敗したので Vault に返金
            vault.deposit(player, amount)
            val msg = result.exceptionOrNull()?.message ?: "不明なエラー"
            Messages.error(plugin, player, "入金に失敗しました: $msg")
        }
    }

    private fun depositRequest(sender: Player, amount: Double): DepositRequest {
        return DepositRequest(
            uuid = sender.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = "PlayerDepositOnCommand",
            displayNote = "/depositによる入金",
            server = plugin.serverName
        )
    }
}
