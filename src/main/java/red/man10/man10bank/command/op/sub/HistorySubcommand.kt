package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.EstateService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.Messages

/**
 * /bankop history <player> [page]
 * プレイヤーの資産履歴をページネーション表示（1ページ10件）。
 * 各行は「日付 トータル」を表示し、ホバーで内訳を表示する。
 */
class HistorySubcommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val estateService: EstateService,
) : BankOpSubcommand {
    override val name: String = "history"
    override val usage: String = "/bankop history <player> [page] - 資産履歴を表示"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 2) {
            Messages.error(sender, "プレイヤー名を指定してください。例: /bankop history <player> [page]")
            return true
        }
        val targetName = args[1]
        val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = 10
        val offset = (page - 1) * limit

        val offline = Bukkit.getOfflinePlayer(targetName)
        val uuid = offline.uniqueId

        scope.launch {
            val list = estateService.history(uuid, limit, offset)
            if (list.isEmpty()) {
                Messages.warn(plugin, sender, "履歴が見つかりません（${targetName}, page=${page}）。")
                return@launch
            }
            // 見出し
            Messages.send(plugin, sender, "§e§l[資産履歴] §f対象: §b$targetName §fページ: §b$page")

            list.forEach { h ->
                val dateText = h.date?.let { DateFormats.toDateTime(it) } ?: "-"
                val totalText = BalanceFormats.coloredYen(h.total ?: 0.0)

                val hoverLines = buildString {
                    appendLine("内訳:")
                    h.vault?.let { appendLine("・電子マネー(Vault): ${BalanceFormats.amount(it)} 円") }
                    h.bank?.let { appendLine("・銀行: ${BalanceFormats.amount(it)} 円") }
                    h.cash?.let { appendLine("・現金: ${BalanceFormats.amount(it)} 円") }
                    h.estateAmount?.let { appendLine("・小切手: ${BalanceFormats.amount(it)} 円") }
                    h.shop?.let { appendLine("・ショップ: ${BalanceFormats.amount(it)} 円") }
                    h.crypto?.let { appendLine("・暗号資産: ${BalanceFormats.amount(it)} 円") }
                    h.loan?.let { appendLine("・ローン: ${BalanceFormats.amount(it)} 円") }
                    append("合計: ${BalanceFormats.amount(h.total ?: 0.0)} 円")
                }

                val comp = Component.text("${Messages.PREFIX}§7${dateText} §f: §b§l${totalText}")
                    .hoverEvent(HoverEvent.showText(Component.text(hoverLines)))

                // プレイヤーはホバー付き、コンソールはテキストのみ
                if (sender is Player) {
                    sender.sendMessage(comp)
                } else {
                    Messages.send(plugin, sender, "${dateText}: ${totalText}")
                }
            }
        }
        return true
    }
}
