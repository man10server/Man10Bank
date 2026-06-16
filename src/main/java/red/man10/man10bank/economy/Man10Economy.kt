package red.man10.man10bank.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats

/**
 * Man10Bank を Vault(Economy) の Provider 化する実装（VaultProvider 8.2）。
 * - [VaultService] への薄いアダプタ。残高は user_vault を真実とし、各サーバーはキャッシュを持つ。
 * - 旧 Vault（単一通貨・double・整数円）のみ対応。bank 系・多通貨は非対応。
 * - 非推奨の文字列/ワールド版メソッドは OfflinePlayer 版へ委譲する。
 *
 * 同期メソッド（getBalance/has/withdrawPlayer/depositPlayer）はメインスレッドから呼ばれる前提
 * （キャッシュ参照のみで完結。8.4）。
 */
class Man10Economy(
    private val plugin: JavaPlugin,
    private val vault: VaultService,
    private val currencySingular: String,
    private val currencyPlural: String,
) : Economy {

    override fun isEnabled(): Boolean = vault.isReady()

    override fun getName(): String = "Man10Bank"

    override fun hasBankSupport(): Boolean = false

    // 円・整数。小数桁は持たない。
    override fun fractionalDigits(): Int = 0

    override fun format(amount: Double): String = "${BalanceFormats.amount(amount)}$currencySingular"

    override fun currencyNamePlural(): String = currencyPlural

    override fun currencyNameSingular(): String = currencySingular

    // === 口座 ===
    override fun hasAccount(player: OfflinePlayer): Boolean = true

    override fun hasAccount(player: OfflinePlayer, worldName: String?): Boolean = hasAccount(player)

    override fun createPlayerAccount(player: OfflinePlayer): Boolean =
        vault.ensureAccount(player.uniqueId, player.name.orEmpty())

    override fun createPlayerAccount(player: OfflinePlayer, worldName: String?): Boolean =
        createPlayerAccount(player)

    // === 残高 ===
    override fun getBalance(player: OfflinePlayer): Double = vault.getBalance(player.uniqueId)

    override fun getBalance(player: OfflinePlayer, world: String?): Double = getBalance(player)

    override fun has(player: OfflinePlayer, amount: Double): Boolean = vault.has(player.uniqueId, amount)

    override fun has(player: OfflinePlayer, worldName: String?, amount: Double): Boolean = has(player, amount)

    // === 入出金 ===
    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        vault.withdraw(player, amount)

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        vault.deposit(player, amount)

    override fun depositPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    // === 非推奨: 文字列(プレイヤー名)版は OfflinePlayer 版へ委譲 ===
    @Suppress("DEPRECATION")
    private fun offline(name: String): OfflinePlayer = plugin.server.getOfflinePlayer(name)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun hasAccount(playerName: String): Boolean = hasAccount(offline(playerName))

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun hasAccount(playerName: String, worldName: String?): Boolean = hasAccount(offline(playerName))

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun getBalance(playerName: String): Double = getBalance(offline(playerName))

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun getBalance(playerName: String, world: String?): Double = getBalance(offline(playerName))

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun has(playerName: String, amount: Double): Boolean = has(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun has(playerName: String, worldName: String?, amount: Double): Boolean = has(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse =
        withdrawPlayer(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun withdrawPlayer(playerName: String, worldName: String?, amount: Double): EconomyResponse =
        withdrawPlayer(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse =
        depositPlayer(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun depositPlayer(playerName: String, worldName: String?, amount: Double): EconomyResponse =
        depositPlayer(offline(playerName), amount)

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun createPlayerAccount(playerName: String): Boolean = createPlayerAccount(offline(playerName))

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun createPlayerAccount(playerName: String, worldName: String?): Boolean =
        createPlayerAccount(offline(playerName))

    // === bank 系: すべて未対応（VaultProvider 8.2） ===
    override fun createBank(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun createBank(name: String?, player: String?): EconomyResponse = notImplemented()

    override fun deleteBank(name: String?): EconomyResponse = notImplemented()

    override fun bankBalance(name: String?): EconomyResponse = notImplemented()

    override fun bankHas(name: String?, amount: Double): EconomyResponse = notImplemented()

    override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = notImplemented()

    override fun bankDeposit(name: String?, amount: Double): EconomyResponse = notImplemented()

    override fun isBankOwner(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = notImplemented()

    override fun isBankMember(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()

    @Deprecated("Use OfflinePlayer", ReplaceWith(""))
    override fun isBankMember(name: String?, playerName: String?): EconomyResponse = notImplemented()

    override fun getBanks(): MutableList<String> = mutableListOf()

    private fun notImplemented(): EconomyResponse =
        EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "銀行APIは未対応です。")
}
