package red.man10.man10bank.command.op.sub

import org.bukkit.command.CommandSender
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import kotlin.math.abs

/**
 * /bankop editvault <player> <金額> <理由> - 指定プレイヤーの電子マネー残高を調整
 */
class SetVaultSubcommand(
    private val vaultManager: VaultManager,
) : EditBalanceSubcommand(
    name = "editvault",
    usageDescription = "電子マネー残高を指定額に調整",
) {
    override fun preValidate(sender: CommandSender): Boolean {
        if (!vaultManager.isAvailable()) {
            Messages.error(sender, "電子マネーが利用できません。Vault連携を確認してください。")
            return false
        }
        return true
    }

    override fun executeEdit(sender: CommandSender, target: EditTarget) {
        val targetAmount = target.amount.toLong().toDouble()
        val current = vaultManager.getBalance(target.offline)

        if (targetAmount == current) {
            Messages.send(sender, "残高は既に ${BalanceFormats.coloredYen(current)} です。")
            return
        }

        val diff = targetAmount - current
        val ok = if (diff > 0.0) {
            vaultManager.deposit(target.offline, diff)
        } else {
            vaultManager.withdraw(target.offline, abs(diff))
        }

        if (!ok) {
            Messages.error(sender, "電子マネー残高の調整に失敗しました。")
            return
        }

        val diffText = if (diff >= 0.0) {
            "+${BalanceFormats.coloredYen(diff)}"
        } else {
            "-${BalanceFormats.coloredYen(abs(diff))}"
        }
        val newBalance = vaultManager.getBalance(target.offline)
        Messages.send(
            sender,
            "電子マネー残高を調整しました。対象: ${target.displayName} 変更: $diffText 変更後: ${BalanceFormats.coloredYen(newBalance)}"
        )
    }
}
