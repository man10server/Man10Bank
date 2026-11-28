package red.man10.man10bank.command.serverloan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.service.PlayerLookupService
import red.man10.man10bank.service.PlayerLookupService.ResolvedPlayer
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /mrevo link の処理: サーバーローンの契約を他のプレイヤーと共有する（残債・借入枠・延滞の状態を共有する）。
 * - /mrevo link: 共有の状態を表示
 * - /mrevo link request <プレイヤー>: 相手の契約への参加を申請
 * - /mrevo link accept <プレイヤー>: 自分の契約への参加申請を承認
 * - /mrevo link deny <プレイヤー>: 自分の契約への参加申請を拒否
 * - /mrevo link cancel: 自分の出した申請を取り消す
 * - /mrevo link leave: 参加している契約から脱退（契約の残債が 0 のときだけ）
 *
 * 申請と承認は残債の合算を伴い、解除しても元に戻らないため、同じコマンドをもう一度実行して確認する。
 */
class ServerLoanLinkHandler(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val service: ServerLoanService,
    private val playerLookup: PlayerLookupService,
) {

    private enum class ConfirmAction { REQUEST, ACCEPT }

    private data class PendingConfirm(val action: ConfirmAction, val target: UUID, val expiresAt: Long)

    // 確認待ちは非メインスレッドのコルーチンからも読み書きするためスレッドセーフにする（DESIGN 3.5）。
    private val confirmations: MutableMap<UUID, PendingConfirm> = ConcurrentHashMap()

    fun handle(player: Player, args: List<String>) {
        if (!service.ensureEnabled(player)) return
        val targetName = args.getOrNull(1)
        when (args.getOrNull(0)?.lowercase()) {
            null -> scope.launch { showStatus(player) }
            "request" -> withTarget(player, targetName, "request") { requestWithConfirm(player, it) }
            "accept" -> withTarget(player, targetName, "accept") { acceptWithConfirm(player, it) }
            "deny" -> withTarget(player, targetName, "deny") { deny(player, it) }
            "cancel" -> scope.launch { report(player, service.cancelLinkRequest(player.uniqueId), "参加の申請を取り消しました。", "申請の取り消しに失敗しました") }
            "leave" -> scope.launch { report(player, service.leaveLink(player.uniqueId), "契約から脱退しました。以後は自分の契約を使います。", "脱退に失敗しました") }
            else -> sendHelp(player)
        }
    }

    fun tabComplete(args: List<String>): List<String> = when (args.size) {
        1 -> SUBCOMMANDS
        2 -> if (args[0].lowercase() in TARGET_SUBCOMMANDS) Bukkit.getOnlinePlayers().map { it.name }.sorted() else emptyList()
        else -> emptyList()
    }

    fun helpLines(): List<String> = listOf(
        "§b/mrevo link §7- リボの共有の状態を表示",
        "§b/mrevo link request <プレイヤー> §7- 相手の契約への参加を申請（残債・枠・延滞を共有）",
        "§b/mrevo link accept <プレイヤー> §7- 自分の契約への参加申請を承認",
        "§b/mrevo link deny <プレイヤー> §7- 自分の契約への参加申請を拒否",
        "§b/mrevo link cancel §7- 自分の出した申請を取り消す",
        "§b/mrevo link leave §7- 参加している契約から脱退（契約の残債が0円のときのみ）",
    )

    private fun sendHelp(player: Player) {
        Messages.sendMultiline(player, helpLines().joinToString("\n"))
    }

    /** プレイヤー名を解決してから処理する。解決できなければ理由を伝える。 */
    private fun withTarget(player: Player, targetName: String?, sub: String, block: suspend (ResolvedPlayer) -> Unit) {
        if (targetName.isNullOrBlank()) {
            Messages.warn(player, "使い方: /mrevo link $sub <プレイヤー>")
            return
        }
        scope.launch {
            val resolved = playerLookup.resolve(targetName)
            val target = resolved.getOrNull()
            if (target == null) {
                Messages.error(plugin, player, playerLookup.failureMessage(targetName, resolved))
                return@launch
            }
            block(target)
        }
    }

    private suspend fun showStatus(player: Player) {
        val result = service.contract(player.uniqueId)
        val contract = result.getOrNull()
        if (contract == null) {
            Messages.error(plugin, player, "共有の状態の取得に失敗しました: ${result.errorMessage()}")
            return
        }
        val lines = listOf("§e§n == リボの共有 ==") + statusLines(player, contract) + helpLines()
        Messages.sendMultiline(plugin, player, lines.joinToString("\n"))
    }

    private fun statusLines(player: Player, contract: ServerLoanContract): List<String> =
        ServerLoanContractView.lines(contract, player.uniqueId, player.name)

    private suspend fun requestWithConfirm(player: Player, target: ResolvedPlayer) {
        if (target.uuid == player.uniqueId) {
            Messages.error(plugin, player, "自分自身の契約には申請できません。")
            return
        }
        if (!confirmed(player, ConfirmAction.REQUEST, target.uuid)) {
            Messages.sendMultiline(plugin, player, """
                §l${target.name} のリボの契約への参加を申請します
                §7- 承認されると、あなたの残債は相手の契約に合算されます（解除しても戻りません）
                §7- 残債・借入枠・延滞の状態を、相手の契約の全員で共有します
                §7- 定期返済と延滞分の回収は、契約の全員の銀行口座から引き落とされます
                §7- 脱退できるのは、契約の残債が0円のときだけです
                §lもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
            """.trimIndent())
            return
        }
        report(
            player, service.requestLink(player.uniqueId, target.uuid),
            "${target.name} の契約への参加を申請しました。相手が §f/mrevo link accept ${player.name} §aを実行すると共有が始まります。",
            "参加の申請に失敗しました",
        )
    }

    private suspend fun acceptWithConfirm(player: Player, member: ResolvedPlayer) {
        if (!confirmed(player, ConfirmAction.ACCEPT, member.uuid)) {
            Messages.sendMultiline(plugin, player, """
                §l${member.name} からの参加申請を承認します
                §7- 相手の残債が、あなたの契約に合算されます（解除しても戻りません）
                §7- 相手もあなたの契約の借入枠で借りられるようになります。借りた分は契約の残債になります
                §7- 定期返済と延滞分の回収は、契約の全員の銀行口座から引き落とされます
                §lもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
            """.trimIndent())
            return
        }
        report(player, service.approveLink(player.uniqueId, member.uuid), "${member.name} の参加を承認しました。", "承認に失敗しました")
    }

    private suspend fun deny(player: Player, member: ResolvedPlayer) {
        report(player, service.rejectLink(player.uniqueId, member.uuid), "${member.name} からの参加申請を拒否しました。", "申請の拒否に失敗しました")
    }

    /**
     * 確認つき操作の2回目かどうか。1回目（または内容が違う・期限切れ）なら確認待ちを記録して false を返す。
     */
    private fun confirmed(player: Player, action: ConfirmAction, target: UUID): Boolean {
        val now = System.currentTimeMillis()
        val pending = confirmations[player.uniqueId]
        if (pending != null && pending.action == action && pending.target == target && now <= pending.expiresAt) {
            confirmations.remove(player.uniqueId)
            return true
        }
        confirmations[player.uniqueId] = PendingConfirm(action, target, now + CONFIRM_WINDOW_MS)
        return false
    }

    /** 結果を伝える。成功時は操作後の共有の状態も表示する。 */
    private fun report(player: Player, result: Result<ServerLoanContract>, successMessage: String, failurePrefix: String) {
        val contract = result.getOrNull()
        if (contract == null) {
            Messages.error(plugin, player, "$failurePrefix: ${result.errorMessage()}")
            return
        }
        val lines = listOf("§a$successMessage") + statusLines(player, contract)
        Messages.sendMultiline(plugin, player, lines.joinToString("\n"))
    }

    private companion object {
        const val CONFIRM_WINDOW_MS = 30_000L
        val SUBCOMMANDS = listOf("request", "accept", "deny", "cancel", "leave")
        val TARGET_SUBCOMMANDS = setOf("request", "accept", "deny")
    }
}
