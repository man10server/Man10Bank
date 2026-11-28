package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.command.serverloan.ServerLoanContractView
import red.man10.man10bank.service.PlayerLookupService
import red.man10.man10bank.service.PlayerLookupService.ResolvedPlayer
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage

/**
 * /bankop serverloan - サーバーローンの契約の共有（リンク）を運営が操作する。
 * - info <プレイヤー>: そのプレイヤーが使っている契約の本垢・参加者・承認待ちの申請を表示
 * - link <参加させるプレイヤー> <本垢> [resetlimit]: 申請・承認なしで参加させる（延滞中でも可）。
 *   参加させるプレイヤーの残債は本垢の契約へ合算される。resetlimit を付けると、罰則として契約の借入枠を初期枠に戻す。
 * - unlink <プレイヤー>: 参加（または承認待ちの申請）を取り消す。契約に残債があっても行える。合算した残債は戻らない。
 *
 * 延滞の状態は本垢の契約のものを共有するため、延滞しているアカウントがあるときは、そちらを本垢に指定する。
 */
class ServerLoanLinkSubcommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val service: ServerLoanService,
    private val playerLookup: PlayerLookupService,
) : BankOpSubcommand {

    override val name: String = "serverloan"
    override val usage: String =
        "/bankop serverloan <info <player>|link <参加させるplayer> <本垢> [resetlimit]|unlink <player>> - リボの契約の共有を操作"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        when (args.getOrNull(1)?.lowercase()) {
            "info" -> info(sender, args.getOrNull(2))
            "link" -> link(sender, args.getOrNull(2), args.getOrNull(3), args.getOrNull(4))
            "unlink" -> unlink(sender, args.getOrNull(2))
            else -> Messages.warn(sender, "使い方: $usage")
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) return ACTIONS
        val players = Bukkit.getOnlinePlayers().map { it.name }.sorted()
        return when (args[0].lowercase()) {
            "info", "unlink" -> if (args.size == 2) players else emptyList()
            "link" -> when (args.size) {
                2, 3 -> players
                4 -> listOf(RESET_LIMIT_FLAG)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun info(sender: CommandSender, targetName: String?) {
        if (targetName.isNullOrBlank()) {
            Messages.warn(sender, "使い方: /bankop serverloan info <player>")
            return
        }
        scope.launch {
            val target = resolve(sender, targetName) ?: return@launch
            val result = service.contract(target.uuid)
            val contract = result.getOrNull()
            if (contract == null) {
                Messages.error(plugin, sender, "契約の取得に失敗しました: ${result.errorMessage()}")
                return@launch
            }
            sendContract(sender, "§e§n == ${target.name} のリボの契約 ==", target, contract)
        }
    }

    private fun link(sender: CommandSender, memberName: String?, ownerName: String?, flag: String?) {
        if (memberName.isNullOrBlank() || ownerName.isNullOrBlank() || (flag != null && !flag.equals(RESET_LIMIT_FLAG, ignoreCase = true))) {
            Messages.warn(sender, "使い方: /bankop serverloan link <参加させるplayer> <本垢> [$RESET_LIMIT_FLAG]")
            return
        }
        val resetLimit = flag != null
        scope.launch {
            val member = resolve(sender, memberName) ?: return@launch
            val owner = resolve(sender, ownerName) ?: return@launch
            val result = service.forceLink(member.uuid, owner.uuid, sender.name, resetLimit)
            val contract = result.getOrNull()
            if (contract == null) {
                Messages.error(plugin, sender, "リンクに失敗しました: ${result.errorMessage()}")
                return@launch
            }
            val penalty = if (resetLimit) "（契約の借入枠を初期枠に戻しました）" else ""
            sendContract(sender, "§a${member.name} を ${owner.name} の契約に参加させました$penalty", member, contract)
        }
    }

    private fun unlink(sender: CommandSender, targetName: String?) {
        if (targetName.isNullOrBlank()) {
            Messages.warn(sender, "使い方: /bankop serverloan unlink <player>")
            return
        }
        scope.launch {
            val target = resolve(sender, targetName) ?: return@launch
            val result = service.forceUnlink(target.uuid, sender.name)
            val contract = result.getOrNull()
            if (contract == null) {
                Messages.error(plugin, sender, "解除に失敗しました: ${result.errorMessage()}")
                return@launch
            }
            sendContract(sender, "§a${target.name} の共有を解除しました（合算した残債は元の契約に戻りません）", target, contract)
        }
    }

    private suspend fun resolve(sender: CommandSender, name: String): ResolvedPlayer? {
        val resolved = playerLookup.resolve(name)
        val target = resolved.getOrNull()
        if (target == null) {
            Messages.error(plugin, sender, playerLookup.failureMessage(name, resolved))
        }
        return target
    }

    private fun sendContract(sender: CommandSender, header: String, target: ResolvedPlayer, contract: ServerLoanContract) {
        val lines = listOf(header) + ServerLoanContractView.lines(contract, target.uuid, target.name)
        Messages.sendMultiline(plugin, sender, lines.joinToString("\n"))
    }

    private companion object {
        const val RESET_LIMIT_FLAG = "resetlimit"
        val ACTIONS = listOf("info", "link", "unlink")
    }
}
