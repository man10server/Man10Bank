package red.man10.man10bank.command.atm

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.ui.atm.AtmDepositUI
import red.man10.man10bank.ui.atm.AtmMainUI
import red.man10.man10bank.util.Messages

class AtmCommand(
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
    private val cashExchangeService: CashExchangeService
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

        val arg = args.getOrNull(0)

        when(arg) {
            "deposit" -> AtmDepositUI(sender, cashItemManager, cashExchangeService).open()
            else -> AtmMainUI(sender, vault).open()

        }

        return true
    }
}
