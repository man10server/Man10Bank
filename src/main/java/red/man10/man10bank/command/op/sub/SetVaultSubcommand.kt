package red.man10.man10bank.command.op.sub

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import kotlin.math.abs

/**
 * /bankop setvault <player> <金額> <理由> - 指定プレイヤーの電子マネー残高を調整
 */
class SetVaultSubcommand(
    private val vaultManager: VaultManager,
) : BankOpSubcommand {
    override val name: String = "setvault"
    override val usage: String = "/bankop setvault <player> <金額> <理由> - 電子マネー残高を指定額に調整"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 4) {
            Messages.error(sender, "使い方: /bankop setvault <player> <金額> <理由>")
            return true
        }
        if (!vaultManager.isAvailable()) {
            Messages.error(sender, "電子マネーが利用できません。Vault連携を確認してください。")
            return true
        }

        val targetName = args[1]
        val amount = args[2].toDoubleOrNull()
        if (amount == null) {
            Messages.error(sender, "金額は数値で指定してください。")
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            Messages.error(sender, "理由を指定してください。")
            return true
        }
        if (amount < 0.0) {
            Messages.error(sender, "金額が不正です。0円以上を指定してください。")
            return true
        }

        val offline = Bukkit.getOfflinePlayer(targetName)
        val displayName = offline.name ?: targetName
        val targetAmount = amount.toLong().toDouble()
        val current = vaultManager.getBalance(offline)

        if (targetAmount == current) {
            Messages.send(sender, "残高は既に ${BalanceFormats.coloredYen(current)} です。")
            return true
        }

        val diff = targetAmount - current
        val ok = if (diff > 0.0) {
            vaultManager.deposit(offline, diff)
        } else {
            vaultManager.withdraw(offline, abs(diff))
        }

        if (!ok) {
            Messages.error(sender, "電子マネー残高の調整に失敗しました。")
            return true
        }

        val diffText = if (diff >= 0.0) {
            "+${BalanceFormats.coloredYen(diff)}"
        } else {
            "-${BalanceFormats.coloredYen(abs(diff))}"
        }
        val newBalance = vaultManager.getBalance(offline)
        Messages.send(
            sender,
            "電子マネー残高を調整しました。対象: $displayName 変更: $diffText 変更後: ${BalanceFormats.coloredYen(newBalance)}"
        )
        return true
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        if (args.size <= 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.sorted()
        }
        return emptyList()
    }
}
