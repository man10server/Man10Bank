package red.man10.man10bank.command

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.service.HealthService
import red.man10.man10bank.util.Messages

/** /man10bank コマンド: ヘルスチェックを表示（OP/コンソール限定） */
class Man10BankCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val healthService: HealthService,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // OP もしくはコンソールのみ
        val isConsole = sender is ConsoleCommandSender
        if (!isConsole && !sender.isOp) {
            Messages.error(sender, "このコマンドはOPのみ使用できます。")
            return true
        }

        scope.launch {
            try {
                val msg = healthService.buildHealthMessage()
                plugin.server.scheduler.runTask(plugin, Runnable {
                    Messages.sendMultiline(sender, msg)
                })
            } catch (e: Exception) {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    Messages.error(sender, "ヘルスチェック失敗: ${e.message}")
                })
            }
        }
        return true
    }
}
