package red.man10.man10bank.command.serverloan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * /mrevo コマンド。
 * - /mrevo: get と borrowLimit の概要 + クリックでヘルプ表示
 * - /mrevo borrow <金額>: 借入
 * - /mrevo pay <金額>: 返済
 * - /mrevo payment <金額>: 支払額設定
 * - /mrevo log [ページ]: ログ表示（ページ）
 * - /mrevo help: ヘルプ
 */
class ServerLoanCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val service: ServerLoanService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as Player
            scope.launch { showSummary(player) }
            return true
        }

        val sub = args[0].lowercase()
        when (sub) {
            "help" -> { sendHelp(sender); return true }
            "borrow" -> {
                if (args.size < 2) { Messages.warn(sender, "使い方: /mrevo borrow <金額>"); return true }
                val amount = args[1].toDoubleOrNull()
                if (amount == null || amount <= 0.0) { Messages.error(sender, "金額が不正です。正の数を指定してください。"); return true }
                val player = sender as Player
                scope.launch { service.borrow(player, amount) }
                return true
            }
            "pay" -> {
                if (args.size < 2) { Messages.warn(sender, "使い方: /mrevo pay <金額>"); return true }
                val amount = args[1].toDoubleOrNull()
                if (amount == null || amount <= 0.0) { Messages.error(sender, "金額が不正です。正の数を指定してください。"); return true }
                val player = sender as Player
                scope.launch { service.repay(player, amount) }
                return true
            }
            "payment" -> {
                if (args.size < 2) { Messages.warn(sender, "使い方: /mrevo payment <金額>"); return true }
                val amount = args[1].toDoubleOrNull()
                if (amount == null || amount <= 0.0) { Messages.error(sender, "金額が不正です。正の数を指定してください。"); return true }
                val player = sender as Player
                scope.launch { service.setPaymentAmount(player, amount) }
                return true
            }
            "log", "logs" -> {
                val page = args.getOrNull(1)?.toIntOrNull() ?: 0
                val limit = 10
                val player = sender as Player
                scope.launch {
                    val res = service.logs(player, limit, page * limit)
                    if (res.isSuccess) {
                        val lines = res.getOrNull().orEmpty().map { log ->
                            val date = log.date ?: "-"
                            val action = log.action
                            val amount = log.amount?.let { BalanceFormats.colored(it) } ?: "-"
                            "${date} §b${action} §7${amount}"
                        }
                        plugin.server.scheduler.runTask(plugin, Runnable {
                            showPaged(player, lines, page, "mrevo log")
                        })
                    } else {
                        val msg = res.exceptionOrNull()?.message ?: "ログの取得に失敗しました。"
                        Messages.error(plugin, player, msg)
                    }
                }
                return true
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private suspend fun showSummary(player: Player) {
        val getRes = service.get(player)
        val limitRes = service.borrowLimit(player)
        val lines = mutableListOf<String>()
        if (getRes.isSuccess) {
            val loan = getRes.getOrNull()
            val borrow = loan?.borrowAmount?.let { BalanceFormats.colored(it) } ?: "0"
            val payment = loan?.paymentAmount?.let { BalanceFormats.colored(it) } ?: "未設定"
            val last = loan?.lastPayDate ?: "-"
            lines += listOf(
                "§b借入額: $borrow",
                "§b支払額: $payment",
                "§7最終返済: $last",
            )
        } else {
            lines += "§7借入情報を取得できませんでした。"
        }
        if (limitRes.isSuccess) {
            lines += "§b借入上限: ${BalanceFormats.colored(limitRes.getOrNull() ?: 0.0)}"
        } else {
            lines += "§7借入上限を取得できませんでした。"
        }
        Messages.sendMultiline(plugin, player, lines.joinToString("\n"))

        // クリックでヘルプ
        plugin.server.scheduler.runTask(plugin, Runnable {
            val comp: Component = Component.text(Messages.PREFIX)
                .append(Component.text("§b§l§n[ヘルプを表示]").clickEvent(ClickEvent.runCommand("/mrevo help")))
            player.sendMessage(comp)
        })
    }

    private fun sendHelp(sender: CommandSender) {
        val lines = listOf(
            "§b/mrevo §7- 概要を表示（借入状況/上限）",
            "§b/mrevo borrow <金額> §7- 借入",
            "§b/mrevo pay <金額> §7- 返済",
            "§b/mrevo payment <金額> §7- 支払額設定",
            "§b/mrevo log [ページ] §7- ログ表示",
        )
        Messages.sendMultiline(sender, lines.joinToString("\n"))
    }

    override fun tabComplete(sender: CommandSender, label: String, args: Array<out String>): List<String> {
        if (args.isEmpty()) return emptyList()
        return when (args.size) {
            1 -> listOf("help", "borrow", "pay", "payment", "log")
            2 -> when (args[0].lowercase()) {
                "log", "logs" -> listOf("0", "1", "2")
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
