package red.man10.man10bank.util

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * メッセージ送信ユーティリティ。
 * - 全メッセージにプレフィックスを付与
 * - 非メインスレッドから呼ばれてもメインスレッドへディスパッチ（Safe系）
 */
object Messages {
    internal const val PREFIX: String = "§f§l[§e§lMan10Bank§f§l]§f "

    // -----------------
    // 基本（メインスレッド前提）
    // -----------------
    private fun prefixed(message: String): String = PREFIX + message

    fun send(sender: CommandSender, message: String) {
        sender.sendMessage(prefixed(message))
    }

    fun error(sender: CommandSender, message: String) {
        sender.sendMessage(prefixed("§c${message}§r"))
    }

    fun warn(sender: CommandSender, message: String) {
        sender.sendMessage(prefixed("§6${message}§r"))
    }

    fun sendMultiline(sender: CommandSender, message: String) {
        message.split('\n').forEach { line ->
            if (line.isNotBlank()) sender.sendMessage(prefixed(line))
        }
    }

    // -----------------
    // スレッドセーフ（メインへディスパッチ）
    // -----------------
    private fun dispatch(plugin: JavaPlugin, block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }

    fun send(plugin: JavaPlugin, sender: CommandSender, message: String) =
        dispatch(plugin) { send(sender, message) }

    fun error(plugin: JavaPlugin, sender: CommandSender, message: String) =
        dispatch(plugin) { error(sender, message) }

    fun warn(plugin: JavaPlugin, sender: CommandSender, message: String) =
        dispatch(plugin) { warn(sender, message) }

    fun sendMultiline(plugin: JavaPlugin, sender: CommandSender, message: String) =
        dispatch(plugin) { sendMultiline(sender, message) }
}
