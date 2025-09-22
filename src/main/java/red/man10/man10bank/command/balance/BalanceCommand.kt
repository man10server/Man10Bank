package red.man10.man10bank.command.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
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
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
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

        scope.launch {
            val lines = mutableListOf("§e§l===== §kX§e§l${sender.name}のお金§kX §e§l=====")
            lines.addAll(BalanceRegistry.buildLines(sender))
            Messages.sendMultiline(plugin, sender, lines.joinToString("\n"))

            // 末尾にヘルプへの導線を追加
            val comp: Component = Component.text(Messages.PREFIX)
                .append(
                    Component.text("§b§l§n[ここをクリックでコマンドを見る]")
                        .clickEvent(ClickEvent.runCommand("/bal help"))
                )
            sender.sendMessage(comp)
        }
        return true
    }

    private fun showHelp(player: Player) {
        // 型のみ（プレースホルダ）
        val body = """
            §e§l===== §kX§e§l/bal ヘルプ§kX §e§l=====
            §7このコマンドのヘルプは準備中です。
        """.trimIndent()
        Messages.sendMultiline(plugin, player, body)
    }
}
