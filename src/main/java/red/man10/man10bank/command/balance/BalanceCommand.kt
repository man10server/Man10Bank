package red.man10.man10bank.command.balance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private val vault: VaultManager,
    private val bank: BankApiClient,
    private val cash: CashItemManager,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.isNotEmpty()) {
            Messages.warn(sender, "使い方: /bal または /balance")
            return true
        }

        scope.launch {
            val ctx = BalanceRegistry.Context(plugin, bank, vault, cash)
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
