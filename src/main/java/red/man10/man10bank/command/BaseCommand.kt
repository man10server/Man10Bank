package red.man10.man10bank.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.util.Messages

/**
 * コマンド実装のための抽象基底クラス。
 * - コンソール/プレイヤーの許可可否
 * - 一般ユーザーの許可可否（false の場合はOPのみ想定）
 * - 権限チェック（デフォルト: man10bank.user）
 */
abstract class BaseCommand(
    private val plugin: JavaPlugin,
    private val allowPlayer: Boolean,
    private val allowConsole: Boolean,
    private val allowGeneralUser: Boolean,
    private val requiredPermission: String = "man10bank.user",
) : CommandExecutor {

    final override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 送り手種別のチェック
        if (sender is Player) {
            if (!allowPlayer) {
                Messages.error(sender, "このコマンドはプレイヤーからは実行できません。")
                return true
            }
            // 権限チェック
            if (allowGeneralUser) {
                if (!sender.hasPermission(requiredPermission)) {
                    Messages.error(sender, "このコマンドを実行する権限がありません。")
                    return true
                }
            } else {
                if (!sender.isOp) {
                    Messages.error(sender, "このコマンドは管理者のみ実行できます。")
                    return true
                }
            }
            return executePlayer(sender, label, args)
        } else {
            if (!allowConsole) {
                Messages.error(sender, "このコマンドはコンソールからは実行できません。")
                return true
            }
            // コンソールは管理者想定。必要ならここで追加の権限検査を行う
            return executeConsole(sender, label, args)
        }
    }

    /** プレイヤー実行時の処理（必要に応じて実装）。*/
    protected open fun executePlayer(player: Player, label: String, args: Array<out String>): Boolean {
        Messages.error(player, "このコマンドのプレイヤー実装がありません。")
        return true
    }

    /** コンソール実行時の処理（必要に応じて実装）。*/
    protected open fun executeConsole(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        Messages.error(sender, "このコマンドのコンソール実装がありません。")
        return true
    }
}

