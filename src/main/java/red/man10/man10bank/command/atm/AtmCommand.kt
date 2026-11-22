package red.man10.man10bank.command.atm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.service.AtmService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.ui.atm.AtmDepositUI
import red.man10.man10bank.ui.atm.AtmMainUI
import red.man10.man10bank.ui.atm.AtmWithdrawUI
import red.man10.man10bank.util.Messages
import red.man10.man10bank.service.FeatureToggleService
import red.man10.man10bank.service.PlayerLookupService
import java.util.UUID

class AtmCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val atmService: AtmService,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
    private val featureToggles: FeatureToggleService,
    private val playerLookup: PlayerLookupService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.atm",
) {

    companion object {
        private const val LOG_PAGE_SIZE = 10
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.ATM)) {
            Messages.error(plugin, sender, "ATM機能は現在停止中です。")
            return true
        }
        val arg = args.getOrNull(0)
        when (arg) {
            "deposit" -> AtmDepositUI(sender, cashItemManager, atmService).open()
            "withdraw" -> AtmWithdrawUI(sender, cashItemManager, atmService, vault).open()
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
                if (!sender.hasPermission("man10bank.atm.logop")) {
                    Messages.error(sender, "このコマンドは運営のみ実行できます。")
                    return true
                }
                val page = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                // 名前→UUIDはメインスレッドを止めないよう非同期で解決する（未キャッシュの名前で認証サーバーへ問い合わせない）
                scope.launch {
                    val resolved = playerLookup.resolve(targetName)
                    val target = resolved.getOrNull()
                    if (target == null) {
                        Messages.error(plugin, sender, playerLookup.failureMessage(targetName, resolved))
                        return@launch
                    }
                    showLogs(sender, target.uuid, target.name, page)
                }
                return true
            }
            else -> AtmMainUI(sender, vault).open()
        }
        return true
    }

    private fun showLogs(viewer: Player, targetUuid: UUID, targetName: String, page: Int) {
        val offset = page * LOG_PAGE_SIZE
        scope.launch(Dispatchers.IO) {
            try {
                val logs = atmService.logs(targetUuid, limit = LOG_PAGE_SIZE, offset = offset)
                val lines = logs.map { log ->
                    val kind = if (log.deposit == true) "§a§l入金" else "§c§l出金"
                    val amt = BalanceFormats.coloredYen(log.amount ?: 0.0)
                    val date = log.date?.let { DateFormats.toDateTime(it) } ?: ""
                    "§7[$date] §e$kind§r: $amt"
                }
                // ページ送りのコマンド。/atm log はページ番号だけを取るので対象名を付けない（付けるとページ番号として読まれない）
                val commandBase = if (viewer.uniqueId == targetUuid) "atm log" else "atm logop $targetName"
                plugin.server.scheduler.runTask(plugin, Runnable {
                    Messages.send(viewer, "§6===== ${targetName}のATM履歴 =====")
                    showPaged(viewer, lines, page, commandBase)
                })
            } catch (e: Exception) {
                val msg = e.message?.let { " 詳細: $it" } ?: ""
                Messages.error(plugin, viewer, "ATM履歴の取得に失敗しました。対象: $targetName $msg")
            }
        }
    }

    private fun showLogs(player: Player, page: Int) {
        showLogs(player, player.uniqueId, player.name, page)
    }
}
