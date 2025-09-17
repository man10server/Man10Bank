package red.man10.man10bank.command.cheque

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.util.Messages

/** /mcheque - 小切手関連（ユーザー向け） */
class ChequeCommand : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {
    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        // TODO: 小切手の発行/換金など、具体的な処理を今後実装
        Messages.warn(sender, "小切手コマンドは現在準備中です。")
        return true
    }
}

