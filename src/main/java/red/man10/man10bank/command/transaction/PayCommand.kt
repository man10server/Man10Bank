package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.InsufficientBalanceException
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import java.util.UUID

/**
 * /mpay <player> <amount>
 * - all はなし
 * - 二重実行で確認（30秒以内）
 * - Bank の withdraw→deposit を利用
 * - 送金先はオフラインでも可
 * - note/displayNote は暫定メッセージ
 */
class PayCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val bank: BankApiClient,
) : CommandExecutor {

    companion object {
        private const val CONFIRM_WINDOW_MS = 30_000L
        private val confirmations: MutableMap<UUID, Pending> = mutableMapOf()
    }

    private data class Pending(
        val target: UUID,
        val targetName: String,
        val amount: Double,
        val expiresAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now > expiresAt
        fun matches(target: UUID, amount: Double): Boolean = this.target == target && this.amount == amount
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("man10bank.user")) {
            Messages.error(sender, "このコマンドを実行する権限がありません。")
            return true
        }
        if (sender !is Player) {
            Messages.error(sender, "このコマンドはプレイヤーのみ使用できます。")
            return true
        }
        if (args.size != 2) {
            Messages.warn(sender, "使い方: /mpay <player> <amount>")
            return true
        }

        val targetName = args[0]
        val amount = args[1].toDoubleOrNull() ?: -1.0
        if (amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }

        // オフライン許可: 既知/未知にかかわらず UUID を解決
        val targetOffline: OfflinePlayer = plugin.server.getOfflinePlayer(targetName)
        val targetUuid: UUID = targetOffline.uniqueId

        if (sender.uniqueId == targetUuid && !sender.isOp) {
            Messages.error(sender, "自分自身へは送金できません。")
            return true
        }

        val confirmKey = sender.uniqueId
        val now = System.currentTimeMillis()
        val pending = confirmations[confirmKey]

        if (pending == null || !pending.matches(targetUuid, amount) || pending.isExpired(now)) {
            confirmations[confirmKey] = Pending(targetUuid, targetName, amount, now + CONFIRM_WINDOW_MS)
            Messages.sendMultiline(sender,"""
                §l以下の内容で送金します
                §7- 送金相手: §e§l$targetName 
                §7- 送金金額: ${BalanceFormats.colored(amount)}
                §l§nもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
            """.trimIndent())
            return true
        }
        // 確認済み: 一度で削除してから実処理へ
        confirmations.remove(confirmKey)

        scope.launch {
            // 1) 送金元から出金
            val withdraw = bank.withdraw(
                WithdrawRequest(
                    uuid = sender.uniqueId.toString(),
                    amount = amount,
                    pluginName = plugin.name,
                    note = "TransferTo${targetName}",
                    displayNote = "${targetName}へ送金",
                    server = plugin.serverName
                )
            )

            if (!withdraw.isSuccess) {
                val ex = withdraw.exceptionOrNull()
                if (ex is InsufficientBalanceException) {
                    Messages.error(plugin, sender, "銀行残高が不足しています。")
                } else {
                    Messages.error(plugin, sender, "送金に失敗しました(出金失敗): ${ex?.message ?: "不明なエラー"}")
                }
                return@launch
            }

            // 2) 受取人へ入金
            val deposit = bank.deposit(
                DepositRequest(
                    uuid = targetUuid.toString(),
                    amount = amount,
                    pluginName = plugin.name,
                    note = "TransferFrom${sender.name}",
                    displayNote = "${sender.name}からの送金",
                    server = plugin.serverName
                )
            )

            if (deposit.isSuccess) {
                Messages.send(plugin, sender,
                    "送金に成功しました。送金先: $targetName 金額: ${BalanceFormats.colored(amount)}"
                )
                // オンラインなら受取通知（銀行残高は省略）
                plugin.server.getPlayer(targetUuid)?.let {
                    Messages.send(plugin, it, "${sender.name} さんから ${BalanceFormats.colored(amount)} 円の送金を受け取りました。")
                }
                return@launch
            }

            // 3) 受取失敗時は送金元へ返金
            val refund = bank.deposit(
                DepositRequest(
                    uuid = sender.uniqueId.toString(),
                    amount = amount,
                    pluginName = plugin.name,
                    note = "RefundForFailedTransfer", // 暫定
                    displayNote = "/mpay送金失敗の返金", // 暫定
                    server = plugin.serverName
                )
            )

            if (refund.isSuccess) {
                Messages.error(plugin, sender,
                    "送金に失敗しました(入金失敗)。金額は返金されました。返金後残高は銀行でご確認ください。"
                )
            } else {
                Messages.error(plugin, sender,
                    "${BalanceFormats.colored(amount)}円の返金に失敗しました。至急管理者に連絡してください！"
                )
            }
        }
        return true
    }
}
