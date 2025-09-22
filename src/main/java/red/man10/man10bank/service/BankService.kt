package red.man10.man10bank.service

import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats

/**
 * BankApiClient 向けのサービス。
 * - 残高表示プロバイダの登録を担当
 */
class BankService(
    private val api: BankApiClient,
) {
    /** 残高表示プロバイダを登録（銀行）。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(id = "bank", order = 20) { player ->
            val bal = api.getBalance(player.uniqueId).getOrElse { 0.0 }
            if (bal <= 0.0) "" else "§b§l銀行: ${BalanceFormats.colored(bal)}§r"
        }
    }
}

