package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
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
    private val bankService: BankService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

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

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
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

        scope.launch { bankService.transfer(sender, targetUuid, targetName, amount) }
        return true
    }
}
