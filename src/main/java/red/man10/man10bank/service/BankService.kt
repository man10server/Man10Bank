package red.man10.man10bank.service

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.InsufficientBalanceException
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * BankApiClient 向けのサービス。
 * - 残高表示プロバイダの登録
 * - /deposit /withdraw の処理本体
 */
class BankService(
    private val plugin: Man10Bank,
    private val api: BankApiClient,
    private val vault: VaultManager,
) {
    /** 残高表示プロバイダを登録（銀行）。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(id = "bank", order = 20) { player ->
            val bal = api.getBalance(player.uniqueId).getOrElse { 0.0 }
            if (bal <= 0.0) "" else "§b§l銀行: ${BalanceFormats.colored(bal)}§r"
        }
    }

    /** 銀行残高の取得（エラー時はnull）。*/
    suspend fun getBalance(player: Player): Double? =
        api.getBalance(player.uniqueId).getOrNull()

    /** Vault -> Bank 入金処理（メッセージ送信込み）。 */
    suspend fun deposit(player: Player, amount: Double) {
        // Vault から引き落とし
        val withdrew = vault.withdraw(player, amount)
        if (!withdrew) {
            Messages.error(plugin, player, "Vaultからの引き落としに失敗しました。")
            return
        }
        // Bank へ入金
        val result = api.deposit(depositRequest(player, amount))
        if (result.isSuccess) {
            val newBank = result.getOrNull() ?: 0.0
            Messages.send(plugin, player,
                "入金に成功しました。" +
                        "§b金額: ${BalanceFormats.colored(amount)} " +
                        "§b銀行残高: ${BalanceFormats.colored(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.colored(vault.getBalance(player))}"
            )
            return
        }
        // 失敗したので Vault に返金
        vault.deposit(player, amount)
        val msg = result.exceptionOrNull()?.message ?: "不明なエラー"
        Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.colored(amount)}")
    }

    /** Bank -> Vault 出金処理（メッセージ送信込み）。 */
    suspend fun withdraw(player: Player, amount: Double) {
        // 銀行から出金
        val result = api.withdraw(withdrawRequest(player, amount))

        if (!result.isSuccess) {
            val ex = result.exceptionOrNull()
            if (ex is InsufficientBalanceException) {
                Messages.error(plugin, player, "銀行残高が不足しています。")
            } else {
                Messages.error(plugin, player, "出金に失敗しました: ${ex?.message ?: "不明なエラー"}")
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
                        "§b金額: ${BalanceFormats.colored(amount)} " +
                        "§b銀行残高: ${BalanceFormats.colored(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.colored(vault.getBalance(player))}"
            )
            return
        }

        // Vault への入金に失敗したら銀行に返金
        Messages.error(plugin, player, "出金は成功しましたが、Vaultへの反映に失敗しました。銀行に返金します")
        val refundResult = api.deposit(refundRequest(player, amount))
        if (refundResult.isSuccess) {
            Messages.send(plugin, player, "返金に成功しました。銀行残高: ${BalanceFormats.colored(refundResult.getOrNull() ?: 0.0)}")
        } else {
            Messages.error(plugin, player, "${BalanceFormats.colored(amount)}円の返金に失敗しました。至急管理者に連絡してください！")
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
