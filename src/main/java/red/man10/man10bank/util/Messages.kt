package red.man10.man10bank.util

import org.bukkit.command.CommandSender

/**
 * メッセージ送信ユーティリティ。
 * - 全メッセージにのプレフィックスを付与します。
 */
object Messages {
    private const val PREFIX: String = "§f§l[§e§lMan10Bank§f§l]§f "

    /** 単一行にプレフィックスを付けた文字列を返します。 */
    private fun prefixed(message: String): String = PREFIX + message

    /** 単一行メッセージを送信します。 */
    fun send(sender: CommandSender, message: String) {
        sender.sendMessage(prefixed(message))
    }

    /** 複数行メッセージを行ごとにプレフィックス付きで送信します。 */
    fun sendMultiline(sender: CommandSender, message: String) {
        message.split('\n').forEach { line ->
            if (line.isNotBlank()) sender.sendMessage(prefixed(line))
        }
    }
}

