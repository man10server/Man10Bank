package red.man10.man10bank.command.serverloan

import kotlinx.coroutines.CoroutineScope
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.util.Messages

/**
 * /mrevo コマンド（型のみ）。
 * - 実装は後続で追加
 */
class ServerLoanCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val service: ServerLoanService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = true,
    allowGeneralUser = false, // 運営向け想定（OP）
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        // TODO: 実装を後続タスクで追加
        Messages.warn(sender, "このコマンドは現在準備中です。")
        return true
    }

    override fun tabComplete(sender: CommandSender, label: String, args: Array<out String>): List<String> {
        // TODO: 実装を後続タスクで追加
        return emptyList()
    }
}

