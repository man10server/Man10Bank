package red.man10.man10bank.service

import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats

/**
 * Vault(Economy) 連携のシンプルなマネージャー。
 * - hook(): サービスから Economy を取得して保持
 * - deposit/withdraw/getBalance/format の薄いラッパーを提供
 */
class VaultManager(private val plugin: JavaPlugin) {

    @Volatile
    private var economy: Economy? = null

    /** Vault の Economy を取得して保持します。成功時 true。*/
    fun hook(): Boolean {
        val rsp: RegisteredServiceProvider<Economy>? =
            plugin.server.servicesManager.getRegistration(Economy::class.java)
        economy = rsp?.provider
        return economy != null
    }

    /** 利用可能かどうか */
    fun isAvailable(): Boolean = economy != null

    /** 現在の Economy プロバイダを返します（未取得の場合は null）。*/
    fun provider(): Economy? = economy

    /** 残高取得（未接続時は 0.0）。*/
    fun getBalance(player: OfflinePlayer): Double = economy?.getBalance(player) ?: 0.0

    /** 入金（成功時 true）。*/
    fun deposit(player: OfflinePlayer, amount: Double): Boolean =
        economy?.depositPlayer(player, amount)?.transactionSuccess() == true

    /**
     * 出金（成功時 true）。
     * - プレイヤー残高が不足している場合は出金しないで false を返す
     * - 0 以下の金額は拒否
     */
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean {
        val econ = economy ?: return false
        if (amount <= 0.0) return false
        val bal = econ.getBalance(player)
        if (bal + 1e-6 < amount) return false
        return econ.withdrawPlayer(player, amount).transactionSuccess()
    }

    /** 残高表示プロバイダの登録（電子マネー/Vault）。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(
            id = "vault",
            order = 10,
            provider = { player ->
                val cash = getBalance(player)
                "§b§l電子マネー: ${BalanceFormats.coloredYen(cash)}§r"
            }
        )
    }
}
