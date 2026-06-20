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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /pay <player> <amount> : 電子マネー(user_vault)の送金。
 * - 同一 Paper 上でオンラインのプレイヤー間のみ(設計書 §11.3)。
 * - 送金する資産は送金元・送金先ともに電子マネー。Bank 残高間送金は /mpay が担う。
 * - 30 秒以内の二重実行で確認。
 */
class VaultPayCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vaultService: VaultService,
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

    private data class Pending(val target: UUID, val targetName: String, val amount: Long, val expiresAt: Long) {
        fun isExpired(now: Long): Boolean = now > expiresAt
        fun matches(target: UUID, amount: Long): Boolean = this.target == target && this.amount == amount
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 2) {
            Messages.warn(sender, "使い方: /pay <player> <amount>")
            return true
        }

        val targetName = args[0]
        val amount = args[1].toLongOrNull() ?: args[1].toDoubleOrNull()?.toLong() ?: -1L
        if (amount <= 0L) {
            Messages.error(sender, "金額が不正です。正の整数を指定してください。")
            return true
        }

        // 送金先は同一サーバーで現在オンラインのプレイヤーのみ(オフライン UUID は生成しない)。
        val target = plugin.server.getPlayerExact(targetName)
        if (target == null) {
            Messages.error(sender, "送金先 $targetName が同一サーバーでオンラインではありません。")
            return true
        }
        val targetUuid = target.uniqueId
        if (sender.uniqueId == targetUuid) {
            Messages.error(sender, "自分自身へは送金できません。")
            return true
        }

        val now = System.currentTimeMillis()
        val pending = confirmations[sender.uniqueId]
        if (pending == null || !pending.matches(targetUuid, amount) || pending.isExpired(now)) {
            confirmations[sender.uniqueId] = Pending(targetUuid, target.name, amount, now + CONFIRM_WINDOW_MS)
            Messages.sendMultiline(
                sender,
                """
                §l以下の内容で電子マネーを送金します
                §7- 送金相手: §e§l${target.name}
                §7- 送金金額: ${BalanceFormats.coloredYen(amount.toDouble())}
                §l§nもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
                """.trimIndent()
            )
            return true
        }
        confirmations.remove(sender.uniqueId)

        scope.launch {
            val result = vaultService.transfer(sender.uniqueId, targetUuid, amount, "/pay ${target.name}")
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (result.success) {
                    Messages.send(sender, "${BalanceFormats.coloredYen(amount.toDouble())} を ${target.name} へ送金しました。")
                    plugin.server.getPlayer(targetUuid)?.let {
                        Messages.send(it, "${sender.name} から ${BalanceFormats.coloredYen(amount.toDouble())} を受け取りました。")
                    }
                } else {
                    Messages.error(sender, "送金に失敗しました: ${result.errorMessage}")
                }
            })
        }
        return true
    }
}
