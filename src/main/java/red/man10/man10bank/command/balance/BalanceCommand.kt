package red.man10.man10bank.command.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.util.Messages

/**
 * 所持金確認コマンド。登録済みの Provider を順に表示します。
 * 外部から `BalanceRegistry.register(...)` でセクションを拡張できます。
 */
class BalanceCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vaultManager: VaultManager,
    private val cashItemManager: CashItemManager,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.balance",
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player

        // /bal help の型（プレースホルダ）
        if (args.size == 1 && args[0].equals("help", ignoreCase = true)) {
            showHelp(sender)
            return true
        }

        if (args.isNotEmpty()) {
            Messages.warn(sender, "使い方: /bal または /balance")
            return true
        }

        // Bukkit/Vault 依存の値はメインスレッド（このexecuteはメインスレッド）で先に収集する（DESIGN 3.5）。
        // 以降の非メインスレッド処理ではこの context を参照し、Bukkit API を直接呼ばない。
        val context = BalanceRegistry.BalanceContext(
            vaultBalance = vaultManager.getBalance(sender),
            cashTotal = cashItemManager.countTotalCash(sender),
        )

        scope.launch {
            val lines = mutableListOf("§e§l===== §kX§e§l${sender.name}のお金§kX §e§l=====")
            lines.addAll(BalanceRegistry.buildLines(sender, context))

            Bukkit.getScheduler().runTask(plugin, Runnable {
                Messages.sendMultiline(sender, lines.joinToString("\n"))

                val comp: Component = Component.text(Messages.PREFIX).append(
                    Component.text("§b§l§n[ここをクリックでコマンドを見る]")
                        .clickEvent(ClickEvent.runCommand("/bal help"))
                )
                sender.sendMessage(comp)
            })
        }
        return true
    }

    private fun showHelp(player: Player) {
        // 型のみ（プレースホルダ）
        val prefix = Messages.PREFIX
        val pay = Component.text("$prefix §e[電子マネーを友達に送る]  §n/pay").clickEvent(ClickEvent.suggestCommand("/pay "))
        val atm = Component.text("$prefix §a[電子マネーのチャージ・現金化]  §n/atm").clickEvent(ClickEvent.runCommand("/atm"))
        val deposit = Component.text("$prefix §b[電子マネーを銀行に入れる]  §n/deposit").clickEvent(ClickEvent.suggestCommand("/deposit "))
        val withdraw = Component.text("$prefix §c[電子マネーを銀行から出す]  §n/withdraw").clickEvent(ClickEvent.suggestCommand("/withdraw "))
        val revolving = Component.text("$prefix §e[Man10リボを使う]  §n/mrevo").clickEvent(ClickEvent.suggestCommand("/mrevo"))
        val ranking = Component.text("$prefix §6[お金持ちランキング]  §n/mbaltop").clickEvent(ClickEvent.runCommand("/mbaltop"))
        val log = Component.text("$prefix §7[銀行の履歴]  §n/ballog").clickEvent(ClickEvent.runCommand("/ballog"))

        player.sendMessage(pay)
        player.sendMessage(atm)
        player.sendMessage(deposit)
        player.sendMessage(withdraw)
        player.sendMessage(revolving)
        player.sendMessage(ranking)
        player.sendMessage(log)
    }
}
