package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.PlayerLookupService
import red.man10.man10bank.service.PlayerLookupService.ResolvedPlayer
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.runOnMainThread

/**
 * /bankop lookup <player> - 指定プレイヤーの所持金表示（/bal 相当）を運営向けに確認する。
 * - 対象はオンライン／オフラインどちらでも可。名前は [PlayerLookupService]（オンライン→キャッシュ→名簿）で解決する。
 * - 本人の /bal と同じプロバイダ行を表示するが、運営による閲覧であることが一目で分かるよう
 *   見出し・罫線を赤系にし、「[運営閲覧]」の表記と対象情報の脚注を付ける。
 * - 現金（インベントリ）はオフラインの対象では取得できないため「不明」と表示される。
 */
class LookupSubcommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val playerLookup: PlayerLookupService,
    private val vaultManager: VaultManager,
    private val cashItemManager: CashItemManager,
) : BankOpSubcommand {
    override val name: String = "lookup"
    override val usage: String = "/bankop lookup <player> - 指定プレイヤーの所持金表示を確認"

    private data class Snapshot(val online: Boolean, val context: BalanceRegistry.BalanceContext)

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        val targetName = args.getOrNull(1)
        if (targetName.isNullOrBlank()) {
            Messages.error(sender, "プレイヤー名を指定してください。例: $usage")
            return true
        }
        scope.launch {
            val resolved = playerLookup.resolve(targetName)
            val target = resolved.getOrNull()
            if (target == null) {
                Messages.error(plugin, sender, playerLookup.failureMessage(targetName, resolved))
                return@launch
            }

            // Bukkit/Vault 依存値はメインスレッドで収集する（DESIGN 3.5）。現金はオンラインの対象のみ集計できる。
            val snapshot = plugin.runOnMainThread {
                val online = plugin.server.getPlayer(target.uuid)
                val offline = plugin.server.getOfflinePlayer(target.uuid)
                Snapshot(
                    online = online != null,
                    context = BalanceRegistry.BalanceContext(
                        vaultBalance = runCatching { vaultManager.getBalance(offline) }.getOrElse { 0.0 },
                        cashTotal = online?.let { cashItemManager.countTotalCash(it) },
                    ),
                )
            }
            val lines = BalanceRegistry.buildLines(BalanceRegistry.BalanceTarget(target.uuid, target.name), snapshot.context)
            Messages.sendMultiline(plugin, sender, render(sender, target, snapshot.online, lines))
        }
        return true
    }

    /** 運営閲覧用の見た目に整形する（本人向け /bal の黄色見出しと区別するため赤系）。 */
    private fun render(sender: CommandSender, target: ResolvedPlayer, online: Boolean, lines: List<String>): String {
        val status = if (online) "§aオンライン" else "§7オフライン"
        val body = lines.flatMap { it.split('\n') }.joinToString("\n") { "§c┃ §r$it" }
        return listOf(
            "§c§l━━━━━ §4§l[運営閲覧] §c§l${target.name}のお金 §c§l━━━━━",
            body,
            "§c┗ §7対象: §f${target.name} §8(${target.uuid}) §7状態: $status §7閲覧者: §f${sender.name}",
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.sorted()
        }
        return emptyList()
    }
}
