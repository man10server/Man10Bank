package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * /withdraw [金額|all] : 銀行(user_bank) -> 電子マネー(user_vault)（未指定は全額）。
 * 設計書 §11.2: Man10BankService の move API を 1 トランザクションで実行する。
 * 「全額」は銀行残高基準のため、金額解決は既存 BankService に委譲する。
 */
class WithdrawCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val bankService: BankService,
    private val vaultService: VaultService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.withdraw",
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size > 1) {
            Messages.warn(sender, "使い方: /withdraw [金額|all]")
            return true
        }
        val arg = args.getOrNull(0)
        scope.launch {
            val amount = bankService.resolveWithdrawAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数、または引数なし/all で全額を指定してください。")
                return@launch
            }
            val r = vaultService.moveBankToVault(sender.uniqueId, amount.toLong(), "/withdraw")
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (r.success) {
                    Messages.send(sender, "${BalanceFormats.coloredYen(amount)} を電子マネーへ出金しました。")
                } else {
                    Messages.error(sender, "出金に失敗しました: ${r.errorMessage}")
                }
            })
        }
        return true
    }
}
