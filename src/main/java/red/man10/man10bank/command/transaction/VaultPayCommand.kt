package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /pay <player> <amount> — 電子マネー送金（VaultProvider 4.5/9）。
 * - 送信者・受取人がともに同一サーバーに在席している場合のみ実行可能。
 *   相手がこのサーバーにいなければ拒否する（オフライン相手への送金は別機能）。
 * - 二重実行で確認（30秒以内）。
 * - サーバー側の単一トランザクション（POST /api/Vault/transfer）へ委譲する。
 */
class VaultPayCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val service: VaultService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.pay",
) {

    companion object {
        private const val CONFIRM_WINDOW_MS = 30_000L
        private val confirmations: MutableMap<UUID, Pending> = ConcurrentHashMap()
    }

    private data class Pending(val target: UUID, val targetName: String, val amount: Double, val expiresAt: Long) {
        fun isExpired(now: Long): Boolean = now > expiresAt
        fun matches(target: UUID, amount: Double): Boolean = this.target == target && this.amount == amount
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 2) {
            Messages.warn(sender, "使い方: /pay <player> <amount>")
            return true
        }

        val targetName = args[0]
        val amount = args[1].toDoubleOrNull() ?: -1.0
        if (amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }

        // 受取人は「このサーバーにオンライン」でなければならない（同一サーバー在席が条件）。
        val target = plugin.server.getPlayerExact(targetName)
        if (target == null) {
            Messages.error(sender, "$targetName はこのサーバーにいないため電子マネーを送金できません。")
            return true
        }
        if (sender.uniqueId == target.uniqueId) {
            Messages.error(sender, "自分自身へは送金できません。")
            return true
        }

        val now = System.currentTimeMillis()
        val pending = confirmations[sender.uniqueId]
        if (pending == null || !pending.matches(target.uniqueId, amount) || pending.isExpired(now)) {
            confirmations[sender.uniqueId] = Pending(target.uniqueId, targetName, amount, now + CONFIRM_WINDOW_MS)
            Messages.sendMultiline(sender, """
                §l以下の内容で電子マネーを送金します
                §7- 送金相手: §e§l$targetName
                §7- 送金金額: ${BalanceFormats.coloredYen(amount)}
                §l§nもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
            """.trimIndent())
            return true
        }
        confirmations.remove(sender.uniqueId)

        scope.launch { runTransfer(sender, target.uniqueId, targetName, amount) }
        return true
    }

    private suspend fun runTransfer(sender: Player, targetUuid: UUID, targetName: String, amount: Double) {
        val result = service.transfer(
            fromUuid = sender.uniqueId,
            toUuid = targetUuid,
            amount = amount,
            note = "VaultPayTo$targetName",
            displayNote = "${targetName}へ電子マネー送金",
        )
        if (result.isSuccess) {
            val newBalance = result.getOrNull() ?: 0.0
            Messages.send(plugin, sender,
                "送金に成功しました。送金先: $targetName 金額: ${BalanceFormats.coloredYen(amount)} " +
                    "§b電子マネー残高: ${BalanceFormats.coloredYen(newBalance)}")
            plugin.server.getPlayer(targetUuid)?.let {
                Messages.send(plugin, it, "${sender.name} さんから ${BalanceFormats.coloredYen(amount)}の電子マネーを受け取りました。")
            }
        } else {
            Messages.error(plugin, sender, "送金に失敗しました: ${result.errorMessage("送金に失敗しました。")}")
        }
    }
}
