package red.man10.man10bank.command.cheque

import org.bukkit.command.CommandSender
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.util.Messages

/** /mcheuqeop - 小切手関連（運営向け） */
class McheuqeopCommand : BaseCommand(
    allowPlayer = true,
    allowConsole = true,
    allowGeneralUser = false,
) {
    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        // TODO: 運営向け小切手管理（発行/無効化/確認など）を今後実装
        Messages.warn(sender, "小切手運営コマンドは現在準備中です。")
        return true
    }
}

