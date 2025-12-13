package red.man10.man10bank.command.op

import org.bukkit.command.CommandSender

/**
 * /bankop サブコマンド用インターフェース
 */
interface BankOpSubcommand {
    val name: String
    val usage: String
    fun handle(sender: CommandSender, args: List<String>): Boolean
    /**
     * Tab補完（このサブコマンド配下の引数用）。
     * args にはサブコマンド名を除いた引数配列を渡す。
     */
    fun tabComplete(sender: CommandSender, args: List<String>): List<String> = emptyList()
}
