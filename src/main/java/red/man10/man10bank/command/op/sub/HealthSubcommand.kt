package red.man10.man10bank.command.op.sub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.HealthService
import red.man10.man10bank.util.Messages

/**
 * /bankop health - ヘルスチェックを表示
 */
class HealthSubcommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val healthService: HealthService,
) : BankOpSubcommand {
    override val name: String = "health"
    override val usage: String = "/bankop health - ヘルスチェックを表示"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        scope.launch {
            try {
                val msg = healthService.buildHealthMessage()
                Messages.sendMultiline(plugin, sender, msg)
            } catch (e: Exception) {
                Messages.error(plugin, sender, "ヘルスチェック失敗: ${e.message}")
            }
        }
        return true
    }
}

