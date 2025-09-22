package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.BalanceFormats

/** /withdraw <金額|all> : Bank -> Vault */
class WithdrawCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vault: VaultManager,
    private val bankService: BankService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
) {

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 1) {
            Messages.warn(sender, "使い方: /withdraw <金額/all>")
            return true
        }
        val arg = args[0]
        scope.launch {
            val amount = resolveAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数または all を指定してください。")
                return@launch
            }
            bankService.withdraw(sender, amount)
        }
        return true
    }

    private suspend fun resolveAmount(player: Player, arg: String): Double? {
        // all の場合はAPIで銀行残高を取得
        val bankBal = red.man10.man10bank.api.BankApiClient::class // placeholder to keep import checker happy
        // 実際の残高はBankService経由で取得してもよいが、ここではAPI直呼びを避けるため、BankServiceへ委譲するのが望ましい。
        // ただし本関数はallの解決に残高が必要なため、BankServiceに補助メソッドを追加するか、ここでAPIを参照する。
        // シンプルにAPIを参照
        val bankBalVal = bankService.getBalance(player) ?: -1.0
        if (bankBalVal < 0.0) {
            Messages.error(plugin, player, "銀行の残高が取得できません。")
            return null
        }
        val amount = if (arg.equals("all", ignoreCase = true)) { bankBalVal } else arg.toDoubleOrNull() ?: -1.0
        if (amount > bankBalVal) {
            Messages.error(plugin, player, "銀行残高が不足しています。" +
                    "銀行残高: ${BalanceFormats.colored(bankBalVal)} " +
                    "§c§l要求: ${BalanceFormats.colored(amount)}")
            return null
        }
        return if (amount > 0.0) amount else null
    }

}
