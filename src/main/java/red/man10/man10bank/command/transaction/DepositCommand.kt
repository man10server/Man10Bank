package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * /deposit [金額|all] : 電子マネー(user_vault) -> 銀行(user_bank)（未指定は全額）。
 * 設計書 §11.2: Man10BankService の move API を 1 トランザクションで実行する。
 */
class DepositCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vaultService: VaultService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.deposit",
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size > 1) {
            Messages.warn(sender, "使い方: /deposit [金額|all]")
            return true
        }
        val arg = args.getOrNull(0)
        // 「全額」は Provider キャッシュの visibleBalance(電子マネー)をメインスレッドで取得する。
        val amount: Long = if (arg == null || arg.equals("all", ignoreCase = true)) {
            vaultService.providerGetVisibleBalance(sender.uniqueId)
        } else {
            arg.toLongOrNull() ?: arg.toDoubleOrNull()?.toLong() ?: -1L
        }
        if (amount <= 0L) {
            Messages.error(sender, "金額が不正です。正の数、または引数なし/all で全額を指定してください。")
            return true
        }

        scope.launch {
            val r = vaultService.moveVaultToBank(sender.uniqueId, amount, "/deposit")
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (r.success) {
                    Messages.send(sender, "${BalanceFormats.coloredYen(amount.toDouble())} を銀行へ入金しました。")
                } else {
                    Messages.error(sender, "入金に失敗しました: ${r.errorMessage}")
                }
            })
        }
        return true
    }
}
