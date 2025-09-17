package red.man10.man10bank.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.TabCompleter
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.util.Messages

/**
 * コマンド実装のための抽象基底クラス。
 * - コンソール/プレイヤーの許可可否
 * - 一般ユーザーの許可可否（false の場合はOPのみ想定）
 * - 権限チェック（デフォルト: man10bank.user）
 */
abstract class BaseCommand(
    private val allowPlayer: Boolean,
    private val allowConsole: Boolean,
    private val allowGeneralUser: Boolean,
    private val requiredPermission: String = "man10bank.user",
) : CommandExecutor, TabCompleter {

    final override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        if (!allowPlayer && sender is Player) {
            Messages.error(sender, "このコマンドはプレイヤーからは実行できません。")
            return true
        }

        if (!allowConsole && sender is ConsoleCommandSender) {
            Messages.error(sender, "このコマンドはコンソールからは実行できません。")
            return true
        }

        if (allowGeneralUser && !sender.hasPermission(requiredPermission)) {
            Messages.error(sender, "このコマンドを実行する権限がありません。")
            return true
        }

        if (!allowGeneralUser && !sender.isOp) {
            Messages.error(sender, "このコマンドは管理者のみ実行できます。")
            return true
        }
        return execute(sender, label, args)
    }

    /**実行時の処理（必要に応じて実装）。*/
    protected open fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        Messages.error(sender, "このコマンドの実装がありません。")
        return true
    }

    // -----------------
    // Tab 補完
    // -----------------
    final override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        // 送り手/権限チェック（エラーメッセージは出さない）
        if (!allowPlayer && sender is Player) return mutableListOf()
        if (!allowConsole && sender is ConsoleCommandSender) return mutableListOf()
        if (allowGeneralUser && !sender.hasPermission(requiredPermission)) return mutableListOf()
        if (!allowGeneralUser && !sender.isOp) return mutableListOf()

        val suggestions = tabComplete(sender, alias, args)
        // 末尾引数で前方一致フィルタ（子クラスが未フィルタの場合の簡易対応）
        val last = args.lastOrNull()?.lowercase() ?: return suggestions.toMutableList()
        return suggestions.filter { it.lowercase().startsWith(last) }.toMutableList()
    }

    /** Tab補完（必要に応じて実装）。*/
    protected open fun tabComplete(sender: CommandSender, label: String, args: Array<out String>): List<String> = emptyList()

    // -----------------
    // ページネーション表示ヘルパー
    // -----------------
    protected fun showPaged(
        player: Player,
        allLines: List<String>,
        page: Int,
        pageSize: Int,
        commandBase: String,
    ) {
        val start = (page * pageSize).coerceAtLeast(0)
        val end = (start + pageSize).coerceAtMost(allLines.size)
        val pageLines = if (start >= end) emptyList() else allLines.subList(start, end)
        val body = if (pageLines.isEmpty()) "履歴がありません" else pageLines.joinToString("\n")
        Messages.sendMultiline(player, body)

        val hasPrev = page > 0
        val hasNext = end < allLines.size
        if (!hasPrev && !hasNext) return

        var comp: Component = Component.text(Messages.PREFIX)
        if (hasPrev) {
            comp = comp.append(Component.text("§b§l§n[前のページ]")
                .clickEvent(ClickEvent.runCommand("/$commandBase ${page - 1}")))
        }
        if (hasNext) {
            if (hasPrev) comp = comp.append(Component.text(" "))
            comp = comp.append(Component.text("§b§l§n[次のページ]")
                .clickEvent(ClickEvent.runCommand("/$commandBase ${page + 1}")))
        }
        player.sendMessage(comp)
    }
}
