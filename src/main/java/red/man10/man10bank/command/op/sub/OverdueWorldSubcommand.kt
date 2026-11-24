package red.man10.man10bank.command.op.sub

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.OverdueWorldGuardService
import red.man10.man10bank.util.Messages

/**
 * /bankop overdueworld <add|remove|list> [ワールド名] - サーバーローン延滞者が入れないワールドを設定
 * - ワールド名を省略すると、実行したプレイヤーが今いるワールドを対象にする。
 * - 変更はその場で反映し、config.yml に保存する。
 */
class OverdueWorldSubcommand(
    private val plugin: Man10Bank,
    private val guard: OverdueWorldGuardService,
) : BankOpSubcommand {

    override val name: String = "overdueworld"
    override val usage: String = "/bankop overdueworld <add|remove|list> [ワールド名] - 延滞者が入れないワールドを設定"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        when (args.getOrNull(1)?.lowercase()) {
            "add" -> add(sender, args.getOrNull(2))
            "remove" -> remove(sender, args.getOrNull(2))
            "list" -> printList(sender)
            else -> Messages.warn(sender, "使い方: $usage")
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) return listOf("add", "remove", "list")
        if (args.size > 2) return emptyList()
        val blocked = guard.blockedWorlds()
        return when (args[0].lowercase()) {
            "add" -> plugin.server.worlds.map { it.name }.filter { it.lowercase() !in blocked }.sorted()
            "remove" -> blocked
            else -> emptyList()
        }
    }

    private fun add(sender: CommandSender, worldArg: String?) {
        val worldName = resolveWorldName(sender, worldArg) ?: return
        val world = plugin.server.worlds.firstOrNull { it.name.equals(worldName, ignoreCase = true) }
        if (world == null) {
            Messages.error(sender, "このサーバーに存在しないワールドです: $worldName")
            return
        }
        if (!guard.addWorld(world.name)) {
            Messages.warn(sender, "${world.name} は既に延滞者の進入禁止ワールドです。")
            return
        }
        Messages.send(sender, "${world.name} を延滞者の進入禁止ワールドに追加しました。")
        printList(sender)
    }

    private fun remove(sender: CommandSender, worldArg: String?) {
        val worldName = resolveWorldName(sender, worldArg) ?: return
        if (!guard.removeWorld(worldName)) {
            Messages.warn(sender, "$worldName は延滞者の進入禁止ワールドではありません。")
            return
        }
        Messages.send(sender, "$worldName を延滞者の進入禁止ワールドから外しました。")
        printList(sender)
    }

    /** ワールド名の引数を解決する。省略時はプレイヤーの現在地。解決できなければ案内を出して null。 */
    private fun resolveWorldName(sender: CommandSender, worldArg: String?): String? {
        if (!worldArg.isNullOrBlank()) return worldArg
        if (sender is Player) return sender.world.name
        Messages.error(sender, "コンソールから実行する場合はワールド名を指定してください。")
        return null
    }

    private fun printList(sender: CommandSender) {
        val worlds = guard.blockedWorlds()
        if (worlds.isEmpty()) {
            Messages.send(sender, "延滞者の進入禁止ワールドは設定されていません。")
            return
        }
        Messages.send(sender, "延滞者の進入禁止ワールド: ${worlds.joinToString(", ")}")
    }
}
