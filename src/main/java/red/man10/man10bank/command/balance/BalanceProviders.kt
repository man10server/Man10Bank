package red.man10.man10bank.command.balance

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.BalanceFormats

/**
 * 標準の所持金表示プロバイダ群。
 */
object BalanceProviders {

    /** 現金（インベントリ + エンダーチェスト） */
    object CashProvider : BalanceRegistry.Provider {
        override suspend fun line(player: Player, ctx: BalanceRegistry.Context): String {
            val total = ctx.cash.countTotalCash(player)
            if (total <= 0.0) return ""
            return "§b§l現金: ${BalanceFormats.colored(total)}§r"
        }
    }

    /** 電子マネー（Vault） */
    object VaultProvider : BalanceRegistry.Provider {
        override suspend fun line(player: Player, ctx: BalanceRegistry.Context): String {
            val cash = ctx.vault.getBalance(player)
            return "§b§l電子マネー: ${BalanceFormats.colored(cash)}§r"
        }
    }

    /** 銀行（Bank API） */
    object BankProvider : BalanceRegistry.Provider {
        override suspend fun line(player: Player, ctx: BalanceRegistry.Context): String {
            val bal = ctx.bank.getBalance(player.uniqueId).getOrElse { 0.0 }
            if (bal <= 0.0) { return "" }
            return "§b§l銀行: ${BalanceFormats.colored(bal)}§r"
        }
    }

    /** 既定のプロバイダをレジストリへ登録。 */
    fun registerDefaults() {
        // order は小さい方が上に表示される
        BalanceRegistry.register(id = "cash", order = 5, provider = CashProvider)
        BalanceRegistry.register(id = "vault", order = 10, provider = VaultProvider)
        BalanceRegistry.register(id = "bank", order = 20, provider = BankProvider)
    }
}
