package red.man10.man10bank.service

import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.java.JavaPlugin

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

    /** 出金（成功時 true）。*/
    fun withdraw(player: OfflinePlayer, amount: Double): Boolean =
        economy?.withdrawPlayer(player, amount)?.transactionSuccess() == true

    /** 金額のフォーマット（プロバイダ未設定時は null）。*/
    fun format(amount: Double): String? = economy?.format(amount)
}

