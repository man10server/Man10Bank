package red.man10.man10bank.command.op

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.op.sub.SetCashSubcommand
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.util.Messages

/**
 * /bankop コマンド（OP専用）: サブコマンドにディスパッチ
 */
class BankOpCommand(
    plugin: Man10Bank,
    cashItemManager: CashItemManager,
) : CommandExecutor {

    private val subcommands: Map<String, BankOpSubcommand> = listOf(
        SetCashSubcommand(cashItemManager),
    ).associateBy { it.name }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp) {
            Messages.error(sender, "このコマンドはOPのみ使用できます。")
            return true
        }

        if (args.isEmpty()) {
            printUsage(sender)
            return true
        }

        val sub = subcommands[args[0].lowercase()]
        if (sub == null) {
            Messages.error(sender, "不明なサブコマンドです: ${args[0]}")
            printUsage(sender)
            return true
        }

        return sub.handle(sender, args.toList())
    }

    private fun printUsage(sender: CommandSender) {
        Messages.send(sender, "使用方法: /bankop <subcommand>")
        subcommands.values.forEach { sc -> Messages.send(sender, sc.usage) }
    }
}
