package red.man10.man10bank.command.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages

/**
 * 所持金確認コマンド。登録済みの Provider を順に表示します。
 * 外部から `BalanceRegistry.register(...)` でセクションを拡張できます。
 */
class BalanceCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vault: VaultManager,
    private val bank: BankApiClient,
) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("man10bank.user")) {
            Messages.error(sender, "このコマンドを実行する権限がありません。")
            return true
        }
        if (sender !is Player) {
            Messages.error(sender, "このコマンドはプレイヤーのみ使用できます。")
            return true
        }
        if (args.isNotEmpty()) {
            Messages.warn(sender, "使い方: /bal または /balance")
            return true
        }

        scope.launch {
            val ctx = BalanceRegistry.Context(plugin, bank, vault)
            val lines = BalanceRegistry.buildLines(sender, ctx)
            if (lines.isEmpty()) {
                Messages.send(plugin, sender, "表示可能な情報がありません。")
            } else {
                Messages.sendMultiline(plugin, sender, lines.joinToString("\n"))
            }
        }
        return true
    }
}

