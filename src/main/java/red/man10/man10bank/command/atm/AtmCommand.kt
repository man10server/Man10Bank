package red.man10.man10bank.command.atm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.command.BaseCommand
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
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    companion object {
        private const val LOG_PAGE_SIZE = 10
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        val arg = args.getOrNull(0)
        when (arg) {
            "deposit" -> AtmDepositUI(sender, cashItemManager, cashExchangeService).open()
            "withdraw" -> AtmWithdrawUI(sender, cashItemManager, cashExchangeService, vault).open()
            "log" -> {
                val page = args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                showLogs(sender, page)
                return true
            }
            "logop" -> {
                val targetName = args.getOrNull(1)
                if (targetName.isNullOrBlank()) {
                    Messages.warn(sender, "使い方: /atm logop <playerName> [page]")
                    return true
                }
                if (!sender.isOp) {
                    Messages.error(sender, "このコマンドは運営のみ実行できます。")
                    return true
                }
                val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                showLogs(sender, targetName, page)
                return true
            }
            else -> AtmMainUI(sender, vault).open()
        }
        return true
    }

    private fun showLogs(viewer: Player, targetName: String, page: Int) {
        val offset = page * LOG_PAGE_SIZE
        val target = plugin.server.getOfflinePlayer(targetName)
        scope.launch(Dispatchers.IO) {
            val result = atmApi.getLogs(target.uniqueId, limit = LOG_PAGE_SIZE, offset = offset)
            if (result.isFailure) {
                Messages.error(plugin, viewer, "ATM履歴の取得に失敗しました。対象: $targetName")
                return@launch
            }
            val logs = result.getOrNull().orEmpty()
            val lines = logs.map { log ->
                val kind = if (log.deposit == true) "§a§l入金" else "§c§l出金"
                val amt = BalanceFormats.colored(log.amount ?: 0.0)
                val date = log.date?.let { DateFormats.fromIsoString(it) } ?: ""
                "§7[$date] §e$kind§r: $amt"
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                Messages.send(viewer, "§6===== ${targetName}のATM履歴 =====")
                showPaged(viewer, lines, page, "atm logop $targetName")
            })
        }
    }

    private fun showLogs(player: Player, page: Int) {
        showLogs(player, player.name, page)
    }
}
