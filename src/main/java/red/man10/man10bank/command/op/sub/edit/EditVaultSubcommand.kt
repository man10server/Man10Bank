package red.man10.man10bank.command.op.sub.edit

import org.bukkit.command.CommandSender
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * /bankop editvault <player> <金額> <理由> - 指定プレイヤーの電子マネー残高を絶対値で設定する。
 * 設計書 §4.3/§7.2: 管理者操作は対象の在席状況を問わず Man10BankService へ権威設定を送る(setBalance)。
 * 衝突や残高制約違反は Service 側で拒否され、コマンド結果として返す。
 */
class EditVaultSubcommand(
    private val vaultManager: VaultManager,
) : EditBalanceSubcommand(
    name = "editvault",
    usageDescription = "電子マネー残高を指定額に設定",
) {
    override fun preValidate(sender: CommandSender): Boolean {
        if (!vaultManager.isAvailable()) {
            Messages.error(sender, "電子マネーサービスが利用できません。")
            return false
        }
        return true
    }

    override fun executeEdit(sender: CommandSender, target: EditTarget) {
        val targetAmount = target.amount.toLong()
        // 在席状況を問わず権威設定を送る(非同期)。結果はメインスレッドで通知される。
        vaultManager.setBalanceAsync(target.offline, targetAmount, "editvault:${target.reason}") { result ->
            if (result.success) {
                Messages.send(
                    sender,
                    "電子マネー残高を設定しました。対象: ${target.displayName} 設定後: ${BalanceFormats.coloredYen(result.balance.toDouble())}"
                )
            } else {
                Messages.error(sender, "電子マネー残高の設定に失敗しました: ${result.errorMessage}")
            }
        }
    }
}
