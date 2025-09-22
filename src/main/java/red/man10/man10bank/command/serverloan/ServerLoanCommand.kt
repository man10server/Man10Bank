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
import red.man10.man10bank.util.DateFormats

/**
 * /mrevo コマンド。
 * - /mrevo: get と borrowLimit の概要 + クリックでヘルプ表示
 * - /mrevo borrow <金額>: 借入
 * - /mrevo pay <金額>: 返済
 * - /mrevo payment <金額>: 支払額設定
 * - /mrevo log <ページ>: ログ表示（ページ）
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
        val player = sender as Player
        if (args.isEmpty()) {
            scope.launch { showSummary(player) }
            return true
        }

        val sub = args[0].lowercase()
        when (sub) {
            "help" -> { sendHelp(sender); return true }
            "borrow" -> {
                if (args.size < 2) { Messages.warn(player, "使い方: /mrevo borrow <金額>"); return true }
                val amount = parseDouble(player, args[1]) ?: return true
                scope.launch { service.borrow(player, amount) }
                return true
            }
            "pay" -> {
                if (args.size < 2) { Messages.warn(player, "使い方: /mrevo pay <金額>"); return true }
                val amount = parseDouble(player, args[1]) ?: return true
                scope.launch { service.repay(player, amount) }
                return true
            }
            "payment" -> {
                if (args.size < 2) { Messages.warn(player, "使い方: /mrevo payment <金額>"); return true }
                val amount = parseDouble(player, args[1]) ?: return true
                scope.launch { service.setPaymentAmount(player, amount) }
                return true
            }
            "log" -> {
                val page = args.getOrNull(1)?.toIntOrNull() ?: 0
                val limit = 10
                scope.launch { showLogs(player, page, limit) }
                return true
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private suspend fun showSummary(player: Player) {
        val getRes = service.get(player)
        val limitRes = service.borrowLimit(player)
        val paymentInfoRes = service.paymentInfo(player)
        val lines = mutableListOf("§e§n == Man10 Revolving Loan ==")
        if (getRes.isSuccess) {
            val loan = getRes.getOrNull()
            val borrow = loan?.borrowAmount?.let { BalanceFormats.colored(it) } ?: "0"
            val payment = loan?.paymentAmount?.let { BalanceFormats.colored(it) } ?: "未設定"
            val last = loan?.lastPayDate?.let { DateFormats.toDateTime(it) } ?: "-"
            lines += listOf(
                "§b借入額: $borrow",
                "§b支払額: $payment",
                "§7最終返済: $last",
            )
        }
        if (limitRes.isSuccess) {
            lines += "§b借入上限: ${BalanceFormats.colored(limitRes.getOrNull() ?: 0.0)}"
        }

        if (paymentInfoRes.isSuccess) {
            val info = paymentInfoRes.getOrNull()
            val next = info?.nextRepayDate?.let { DateFormats.toDateTime(it) } ?: "-"
            val daily = info?.dailyInterestPerDay?.let { BalanceFormats.colored(it) } ?: "-"
            lines += listOf(
                "§b次回返済日: $next",
                "§b1日あたりの利息: $daily",
            )
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
            "§b/mrevo §7- ステータスを表示（借入状況/上限）",
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
            else -> emptyList()
        }
    }

    /**
     * ログ取得と表示（ページング）。
     */
    private suspend fun showLogs(player: Player, page: Int, limit: Int) {
        val res = service.logs(player, limit, page * limit)
        if (res.isSuccess) {
            val lines = res.getOrNull().orEmpty().map { log ->
                val date = log.date?.let { DateFormats.toDateTime(it) } ?: "-"
                val action = log.action
                val amount = log.amount?.let { BalanceFormats.colored(it) } ?: "-"
                "$date §b${action} §7${amount}"
            }
            plugin.server.scheduler.runTask(plugin, Runnable {
                showPaged(player, lines, page, "mrevo log")
            })
        } else {
            val msg = res.exceptionOrNull()?.message ?: "ログの取得に失敗しました。"
            Messages.error(plugin, player, msg)
        }
    }

    /**
     * 金額のパースとバリデーション（正の数）。
     * 不正な場合はエラーメッセージを表示して null を返す。
     */
    private fun parseDouble(player: Player, text: String): Double? {
        val value = text.toDoubleOrNull()
        if (value == null) {
            Messages.error(player, "金額が数値ではありません。")
            return null
        }
        if (value <= 0.0) {
            Messages.error(player, "金額が不正です。正の数を指定してください。")
            return null
        }
        return value
    }
}
