package red.man10.man10bank.command.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.EstateService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * /mbaltop - 富豪ランキング表示（ページネーション）。
 * 参考カラー:
 *  §6§k§lXX§e§l富豪トップ${page*10}§6§k§lXX
 *  §7§l{idx}.§b§l{player} : §e§l{amount}円
 */
class BalTopCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val estateService: EstateService,
    private val serverEstateService: red.man10.man10bank.service.ServerEstateService,
) : BaseCommand(allowPlayer = true, allowConsole = true, allowGeneralUser = true) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        val page = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = 10
        val offset = (page - 1) * limit

        scope.launch {
            val list = estateService.ranking(limit, offset)
            // ヘッダ
            Messages.send(plugin, sender, "§6§k§lXX§e§l富豪トップ${page * 10}§6§k§lXX")

            if (list.isEmpty()) {
                Messages.warn(plugin, sender, "ランキングデータがありません。")
                return@launch
            }

            var i = offset + 1
            val sorted = list.sortedByDescending { it.total ?: 0.0 }
            for (e in sorted) {
                val player = e.player
                val total = BalanceFormats.amount(e.total ?: 0.0)
                Messages.send(plugin, sender, "§7§l${i}.§b§l${player} : §e§l${total}円")
                i++
            }

            val latest = serverEstateService.latest()?: return@launch
            Messages.send(plugin, sender, "§e§l電子マネーの合計:${BalanceFormats.amount(latest.vault ?: 0.0)}円")
            Messages.send(plugin, sender, "§e§l現金の合計:${BalanceFormats.amount(latest.cash ?: 0.0)}円")
            Messages.send(plugin, sender, "§e§l銀行口座の合計:${BalanceFormats.amount(latest.bank ?: 0.0)}円")
            Messages.send(plugin, sender, "§e§lショップ残高の合計:${BalanceFormats.amount(latest.shop ?: 0.0)}円")
            Messages.send(plugin, sender, "§e§lその他資産の合計:${BalanceFormats.amount(latest.estateAmount ?: 0.0)}円")
            Messages.send(plugin, sender, "§c§l公的ローンの合計:${BalanceFormats.amount(latest.loan ?: 0.0)}円")
            Messages.send(plugin, sender, "§e§l全ての合計:${BalanceFormats.amount(latest.total ?: 0.0)}円")
        }

        return true
    }
}
