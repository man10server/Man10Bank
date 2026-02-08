package red.man10.man10bank.command.op.sub.edit

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.util.Messages

/**
 * 残高調整系サブコマンドの共通基底クラス。
 * - /bankop <subcommand> <player> <金額> <理由> の引数処理を提供
 */
abstract class EditBalanceSubcommand(
    final override val name: String,
    usageDescription: String,
) : BankOpSubcommand {
    final override val usage: String = "/bankop $name <player> <金額> <理由> - $usageDescription"

    protected data class EditTarget(
        val offline: OfflinePlayer,
        val displayName: String,
        val amount: Double,
        val reason: String,
    )

    final override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 4) {
            Messages.error(sender, "使い方: /bankop $name <player> <金額> <理由>")
            return true
        }
        if (!preValidate(sender)) {
            return true
        }

        val targetName = args[1]
        val amount = args[2].toDoubleOrNull()
        if (amount == null) {
            Messages.error(sender, "金額は数値で指定してください。")
            return true
        }
        if (amount < 0.0) {
            Messages.error(sender, "金額が不正です。0円以上を指定してください。")
            return true
        }

        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            Messages.error(sender, "理由を指定してください。")
            return true
        }

        val offline = Bukkit.getOfflinePlayerIfCached(targetName)

        if (offline == null) {
            Messages.error(sender,"${targetName}のログイン履歴が見つかりませんでした")
            return true
        }
        val displayName = offline.name ?: targetName
        executeEdit(sender, EditTarget(offline, displayName, amount, reason))
        return true
    }

    protected open fun preValidate(sender: CommandSender): Boolean = true

    protected abstract fun executeEdit(sender: CommandSender, target: EditTarget)

    final override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.sorted()
        }
        return emptyList()
    }
}
