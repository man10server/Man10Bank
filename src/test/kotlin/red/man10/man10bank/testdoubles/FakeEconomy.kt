package red.man10.man10bank.testdoubles

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.bukkit.Bukkit.getOfflinePlayer
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeEconomy : Economy {
    private val balances = ConcurrentHashMap<UUID, Double>()

    override fun getName(): String = "FakeEconomy"
    override fun isEnabled(): Boolean = true
    override fun hasBankSupport(): Boolean = false
    override fun fractionalDigits(): Int = 0
    override fun format(amount: Double): String = amount.toString()
    override fun currencyNamePlural(): String = "dollars"
    override fun currencyNameSingular(): String = "dollar"

    private fun uuidOf(player: OfflinePlayer): UUID = player.uniqueId

    override fun getBalance(player: OfflinePlayer?): Double {
        player ?: return 0.0
        return balances.getOrDefault(uuidOf(player), 0.0)
    }

    override fun depositPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse {
        player ?: return EconomyResponse(0.0, 0.0, ResponseType.FAILURE, "no player")
        if (amount.isNaN() || amount.isInfinite() || amount < 0.0) throw IllegalArgumentException("invalid amount")
        if (amount >= 1.0e16) throw IllegalArgumentException("overflow")
        val id = uuidOf(player)
        val next = balances.getOrDefault(id, 0.0) + amount
        balances[id] = next
        return EconomyResponse(amount, next, ResponseType.SUCCESS, "")
    }

    override fun withdrawPlayer(player: OfflinePlayer?, amount: Double): EconomyResponse {
        player ?: return EconomyResponse(0.0, 0.0, ResponseType.FAILURE, "no player")
        if (amount.isNaN() || amount.isInfinite() || amount < 0.0) throw IllegalArgumentException("invalid amount")
        val id = uuidOf(player)
        val cur = balances.getOrDefault(id, 0.0)
        return if (cur >= amount) {
            val next = cur - amount
            balances[id] = next
            EconomyResponse(amount, next, ResponseType.SUCCESS, "")
        } else {
            EconomyResponse(amount, cur, ResponseType.FAILURE, "insufficient")
        }
    }

    // Name-based variants delegate to OfflinePlayer
    override fun getBalance(playerName: String?): Double = getBalance(getOfflinePlayer(playerName!!))
    override fun withdrawPlayer(playerName: String?, amount: Double): EconomyResponse = withdrawPlayer(getOfflinePlayer(playerName!!), amount)
    override fun depositPlayer(playerName: String?, amount: Double): EconomyResponse = depositPlayer(getOfflinePlayer(playerName!!), amount)

    // World-specific (unused in tests)
    override fun getBalance(playerName: String?, world: String?): Double = getBalance(playerName)
    override fun getBalance(player: OfflinePlayer?, world: String?): Double = getBalance(player)
    override fun has(playerName: String?, amount: Double): Boolean = getBalance(playerName) >= amount
    override fun has(player: OfflinePlayer?, amount: Double): Boolean = getBalance(player) >= amount
    override fun has(playerName: String?, worldName: String?, amount: Double): Boolean = has(playerName, amount)
    override fun has(player: OfflinePlayer?, worldName: String?, amount: Double): Boolean = has(player, amount)
    override fun withdrawPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)
    override fun depositPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(playerName, amount)
    override fun withdrawPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(player, amount)
    override fun depositPlayer(player: OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(player, amount)

    // Bank operations - not implemented in fake
    override fun createBank(name: String?, player: String?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun createBank(name: String?, player: OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun deleteBank(name: String?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankBalance(name: String?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankHas(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun bankDeposit(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankOwner(name: String?, player: OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankMember(name: String?, playerName: String?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun isBankMember(name: String?, player: OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "")
    override fun getBanks(): MutableList<String> = mutableListOf()

    override fun hasAccount(playerName: String?): Boolean = true
    override fun hasAccount(player: OfflinePlayer?): Boolean = true
    override fun hasAccount(playerName: String?, worldName: String?): Boolean = true
    override fun hasAccount(player: OfflinePlayer?, worldName: String?): Boolean = true
    override fun createPlayerAccount(playerName: String?): Boolean = true
    override fun createPlayerAccount(player: OfflinePlayer?): Boolean = true
    override fun createPlayerAccount(playerName: String?, worldName: String?): Boolean = true
    override fun createPlayerAccount(player: OfflinePlayer?, worldName: String?): Boolean = true
}

