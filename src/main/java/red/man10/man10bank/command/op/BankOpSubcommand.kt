package red.man10.man10bank.command.op

import org.bukkit.command.CommandSender

/**
 * /bankop サブコマンド用インターフェース
 */
interface BankOpSubcommand {
    val name: String
    val usage: String
    fun handle(sender: CommandSender, args: List<String>): Boolean
}

