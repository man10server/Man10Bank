package red.man10.man10bank.service

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.api.model.response.MoneyLog
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.Messages
import java.util.UUID

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
            val bal = getBalance(player)?: 0.0
            if (bal <= 0.0) "" else "§b§l銀行: ${BalanceFormats.coloredYen(bal)}§r"
        }
    }

    /** Vault -> Bank 入金処理（メッセージ送信込み）。 */
    suspend fun deposit(player: Player, amount: Double) {
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。1円以上を指定してください。")
            return
        }
        // 電子マネー(Vault) 未接続の場合は安全のため中断
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        // Vault から引き落とし
        val withdrew = vault.withdraw(player, amt)
        if (!withdrew) {
            Messages.error(plugin, player, "電子マネーからの引き落としに失敗しました。")
            return
        }
        // Bank へ入金
        val result = api.deposit(
            depositRequest(
                player = player,
                amount = amt,
                note = "PlayerDepositOnCommand",
                displayNote = "/depositによる入金",
            )
        )
        if (result.isSuccess) {
            val newBank = result.getOrNull() ?: 0.0
            Messages.send(plugin, player,
                "入金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(vault.getBalance(player))}"
            )
            return
        }
        // 失敗したので 電子マネー に返金
        val refunded = vault.deposit(player, amt)
        val msg = result.errorMessage()
        if (!refunded) {
            Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.coloredYen(amt)}。さらに電子マネーへの返金にも失敗しました。管理者に連絡してください。")
        } else {
            Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.coloredYen(amt)}")
        }
    }

    /** Bank -> Vault 出金処理（メッセージ送信込み）。 */
    suspend fun withdraw(player: Player, amount: Double) {
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。1円以上を指定してください。")
            return
        }
        // 電子マネー(Vault) 未接続の場合は安全のため中断（銀行からの出金を行わない）
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        // 銀行から出金
        val result = api.withdraw(
            withdrawRequest(
                player = player,
                amount = amt,
                note = "PlayerWithdrawOnCommand",
                displayNote = "/withdrawによる出金",
            )
        )

        if (!result.isSuccess) {
            val msg = result.errorMessage("出金に失敗しました。")
            Messages.error(plugin, player, "出金に失敗しました: $msg")
            return
        }

        val newBank = result.getOrNull() ?: 0.0

        // Vault に入金
        val ok = vault.deposit(player, amt)
        if (ok) {
            Messages.send(
                plugin,
                player,
                "出金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(vault.getBalance(player))}"
            )
            return
        }

        // 電子マネーへの入金に失敗したら銀行に返金
        Messages.error(plugin, player, "出金は成功しましたが、電子マネーへの反映に失敗しました。銀行に返金します")
        val refundResult = api.deposit(
            depositRequest(
                player = player,
                amount = amt,
                note = "RefundForFailedVaultDeposit",
                displayNote = "電子マネーへの反映失敗による返金",
            )
        )
        if (refundResult.isSuccess) {
            Messages.send(plugin, player, "返金に成功しました。銀行残高: ${BalanceFormats.coloredYen(refundResult.getOrNull() ?: 0.0)}")
        } else {
            val msg = refundResult.errorMessage()
            Messages.error(plugin, player, "${BalanceFormats.coloredYen(amt)}円の返金に失敗しました。$msg")
        }
    }

    /**
     * /deposit 用の金額解決（null または "all" 相当をインジケータとして扱い、Vault残高を返す）。
     * 条件を満たさない場合は null。
     */
    fun resolveDepositAmount(player: Player, arg: String?): Double? {
        if (!vault.isAvailable()) return null
        val vaultBal = vault.getBalance(player)
        val amount = if (arg.isNullOrBlank() || arg.equals("all", ignoreCase = true)) vaultBal else arg.toDoubleOrNull() ?: -1.0
        if (amount > vaultBal) return null
        return if (amount > 0.0) amount else null
    }

    /**
     * /withdraw 用の金額解決（null または "all" 相当で銀行残高）。条件を満たさない場合は null。
     */
    suspend fun resolveWithdrawAmount(player: Player, arg: String?): Double? {
        val bankBal = getBalance(player) ?: return null
        val amount = if (arg.isNullOrBlank() || arg.equals("all", ignoreCase = true)) bankBal else arg.toDoubleOrNull() ?: -1.0
        if (amount > bankBal) return null
        return if (amount > 0.0) amount else null
    }

    /**
     * 振り込み（送金）処理。
     * - 送金元から出金 → 受取人へ入金 → 失敗時は送金元へ返金
     */
    suspend fun transfer(sender: Player, targetUuid: UUID, targetName: String, amount: Double) {
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, sender, "金額が不正です。正の数を指定してください。")
            return
        }
        // 1) 送金元から出金
        val withdraw = api.withdraw(
            withdrawRequest(
                player = sender,
                amount = amt,
                note = "TransferTo${targetName}",
                displayNote = "${targetName}へ送金",
            )
        )

        if (!withdraw.isSuccess) {
            val msg = withdraw.errorMessage("送金に失敗しました(出金失敗)。")
            Messages.error(plugin, sender, "送金に失敗しました(出金失敗)。$msg")
            return
        }

        // 2) 受取人へ入金
        val deposit = api.deposit(
            // 受取人用なので uuid は受取人
            DepositRequest(
                uuid = targetUuid.toString(),
                amount = amt,
                pluginName = plugin.name,
                note = "TransferFrom${sender.name}",
                displayNote = "${sender.name}からの送金",
                server = plugin.serverName,
            )
        )

        if (deposit.isSuccess) {
            Messages.send(plugin, sender,
                "送金に成功しました。送金先: $targetName 金額: ${BalanceFormats.coloredYen(amt)}"
            )
            // オンラインなら受取通知（銀行残高は省略）
            plugin.server.getPlayer(targetUuid)?.let {
                Messages.send(plugin, it, "${sender.name} さんから ${BalanceFormats.coloredYen(amt)}の送金を受け取りました。")
            }
            return
        }

        // 3) 受取失敗時は送金元へ返金
        val refund = api.deposit(
            depositRequest(
                player = sender,
                amount = amt,
                note = "RefundForFailedTransfer",
                displayNote = "/mpay送金失敗の返金",
            )
        )

        if (refund.isSuccess) {
            Messages.error(plugin, sender,
                "送金に失敗しました(入金失敗)。金額は返金されました。返金後残高は銀行でご確認ください。"
            )
        } else {
            val msg = refund.errorMessage()
            Messages.error(plugin, sender, "${BalanceFormats.coloredYen(amt)}の返金に失敗しました。 $msg")
        }
    }

    /**
     * 取引ログの取得（失敗時はProblemDetailsを表示し、nullを返す）。
     */
    suspend fun getLogs(player: Player, limit: Int = 10, offset: Int = 0): List<MoneyLog>? {
        val result = api.getLogs(player.uniqueId, limit, offset)
        if (result.isSuccess) return result.getOrNull()
        val msg = result.errorMessage()
        Messages.error(plugin, player, "ログ取得に失敗しました: $msg")
        return null
    }

    /** 銀行残高の取得（エラー時はnull）。*/
    private suspend fun getBalance(player: Player): Double? =
        api.getBalance(player.uniqueId).getOrNull()


    private fun depositRequest(player: Player, amount: Double, note: String, displayNote: String): DepositRequest =
        DepositRequest(
            uuid = player.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    private fun withdrawRequest(player: Player, amount: Double, note: String, displayNote: String): WithdrawRequest =
        WithdrawRequest(
            uuid = player.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    /**
     * 金額の正規化: 小数点以下を切り捨てて整数（Double）にする。
     * 例) 10.9 -> 10.0, 10.1 -> 10.0
     */
    private fun normalizeAmount(amount: Double): Double = amount.toLong().toDouble()
}
