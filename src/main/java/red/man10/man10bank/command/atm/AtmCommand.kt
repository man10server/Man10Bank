package red.man10.man10bank.command.atm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.ui.atm.AtmDepositUI
import red.man10.man10bank.ui.atm.AtmMainUI
import red.man10.man10bank.ui.atm.AtmWithdrawUI
import red.man10.man10bank.util.Messages

class AtmCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val atmApi: AtmApiClient,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
    private val cashExchangeService: CashExchangeService
) : CommandExecutor {

    companion object {
        private const val LOG_PAGE_SIZE = 10
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

        val arg = args.getOrNull(0)

        when(arg) {
            "deposit" -> AtmDepositUI(sender, cashItemManager, cashExchangeService).open()
            "withdraw" -> AtmWithdrawUI(sender, cashItemManager, cashExchangeService,vault).open()
            "log" -> {
                val page = args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                showLogs(sender, page)
                return true
            }
            else -> AtmMainUI(sender, vault).open()
        }

        return true
    }

    private fun showLogs(player: Player, page: Int) {
        val offset = page * LOG_PAGE_SIZE
        scope.launch(Dispatchers.IO) {
            val result = atmApi.getLogs(player.uniqueId, limit = LOG_PAGE_SIZE, offset = offset)
            if (result.isFailure) {
                Messages.error(plugin, player, "ATM履歴の取得に失敗しました。")
                return@launch
            }
            val logs = result.getOrNull().orEmpty()

            val lines = mutableListOf("§6===== ${player.name}のATM履歴 (ページ ${page + 1}) =====")

            if (logs.isEmpty()) {
                lines.add("履歴がありません")
            } else logs.forEach { log ->
                val kind = if (log.deposit == true) "§a§l入金" else "§c§l出金"
                val amt = BalanceFormats.colored(log.amount ?: 0.0)
                val date = log.date?.let { DateFormats.fromIsoString(it) } ?: ""
                lines.add("§7[$date] §e$kind§r: $amt")
            }

            plugin.server.scheduler.runTask(plugin, Runnable {
                Messages.sendMultiline(player, lines.joinToString("\n"))

                // ページャー
                val compos = Component.text(Messages.PREFIX)
                if (page > 0) {
                    val prev = Component.text("§b§l§n[前のページ]")
                        .clickEvent(ClickEvent.runCommand("/atm log ${page - 1}"))
                    compos.append(prev)
                }
                val hasNext = logs.size >= LOG_PAGE_SIZE
                if (hasNext) {
                    val next = Component.text("§b§l§n[次のページ]")
                        .clickEvent(ClickEvent.runCommand("/atm log ${page + 1}"))
                    compos.append(next)
                }
                player.sendMessage(compos)
            })
        }
    }
}
