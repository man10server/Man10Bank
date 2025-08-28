package red.man10.man10bank.service

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.junit.jupiter.api.*
import red.man10.man10bank.model.ResultCode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import java.math.BigDecimal

class VaultEconomyServiceTest {

    private lateinit var server: ServerMock
    private lateinit var svc: VaultEconomyService

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        svc = VaultEconomyService(FakeEconomy())
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun getBalance_initiallyZero() {
        val p = server.addPlayer("VEconomyUser")
        val bal = svc.getBalance(p.uniqueId)
        Assertions.assertEquals(BigDecimal.ZERO, bal)
    }

    @Test
    fun deposit_success_updatesBalance() {
        val p = server.addPlayer("VEDeposit")
        val res = svc.deposit(p.uniqueId, BigDecimal(150))
        Assertions.assertEquals(ResultCode.SUCCESS, res.code)
        Assertions.assertEquals(BigDecimal(150), res.balance)
        Assertions.assertEquals(BigDecimal(150), svc.getBalance(p.uniqueId))
    }

    @Test
    fun deposit_invalidAmount_returnsInvalid() {
        val p = server.addPlayer("VEInvalidDeposit")
        val res0 = svc.deposit(p.uniqueId, BigDecimal.ZERO)
        val resNeg = svc.deposit(p.uniqueId, BigDecimal(-10))
        Assertions.assertEquals(ResultCode.INVALID_AMOUNT, res0.code)
        Assertions.assertEquals(ResultCode.INVALID_AMOUNT, resNeg.code)
    }

    @Test
    fun deposit_overflow_returnsOverflow() {
        val p = server.addPlayer("VEOverflow")
        val huge = BigDecimal("10000000000000000") // 1e16 -> FakeEconomy throws IAE
        val res = svc.deposit(p.uniqueId, huge)
        Assertions.assertEquals(ResultCode.OVERFLOW, res.code)
    }

    @Test
    fun withdraw_success_updatesBalance() {
        val p = server.addPlayer("VEWithdraw")
        svc.deposit(p.uniqueId, BigDecimal(300))
        val res = svc.withdraw(p.uniqueId, BigDecimal(120))
        Assertions.assertEquals(ResultCode.SUCCESS, res.code)
        Assertions.assertEquals(BigDecimal(180), res.balance)
        Assertions.assertEquals(BigDecimal(180), svc.getBalance(p.uniqueId))
    }

    @Test
    fun withdraw_insufficient_returnsInsufficient() {
        val p = server.addPlayer("VEInsufficient")
        val res = svc.withdraw(p.uniqueId, BigDecimal(50))
        Assertions.assertEquals(ResultCode.INSUFFICIENT_FUNDS, res.code)
    }

    @Test
    fun has_worksCorrectly() {
        val p = server.addPlayer("VEHas")
        svc.deposit(p.uniqueId, BigDecimal(200))
        Assertions.assertTrue(svc.has(p.uniqueId, BigDecimal(150)))
        Assertions.assertTrue(svc.has(p.uniqueId, BigDecimal.ZERO))
        Assertions.assertFalse(svc.has(p.uniqueId, BigDecimal(250)))
    }

    // -----------------------
    // Simple Fake Economy
    // -----------------------
    private class FakeEconomy : Economy {
        private val balances = ConcurrentHashMap<UUID, Double>()

        override fun getName(): String = "FakeEconomy"
        override fun isEnabled(): Boolean = true
        override fun hasBankSupport(): Boolean = false
        override fun fractionalDigits(): Int = 0
        override fun format(amount: Double): String = amount.toString()
        override fun currencyNamePlural(): String = "dollars"
        override fun currencyNameSingular(): String = "dollar"

        private fun uuidOf(player: org.bukkit.OfflinePlayer): UUID = player.uniqueId

        override fun getBalance(player: org.bukkit.OfflinePlayer?): Double {
            player ?: return 0.0
            return balances.getOrDefault(uuidOf(player), 0.0)
        }

        override fun depositPlayer(player: org.bukkit.OfflinePlayer?, amount: Double): EconomyResponse {
            player ?: return EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "no player")
            if (amount.isNaN() || amount.isInfinite() || amount < 0.0) throw IllegalArgumentException("invalid amount")
            if (amount >= 1.0e16) throw IllegalArgumentException("overflow")
            val id = uuidOf(player)
            val next = balances.getOrDefault(id, 0.0) + amount
            balances[id] = next
            return EconomyResponse(amount, next, EconomyResponse.ResponseType.SUCCESS, "")
        }

        override fun withdrawPlayer(player: org.bukkit.OfflinePlayer?, amount: Double): EconomyResponse {
            player ?: return EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "no player")
            if (amount.isNaN() || amount.isInfinite() || amount < 0.0) throw IllegalArgumentException("invalid amount")
            val id = uuidOf(player)
            val cur = balances.getOrDefault(id, 0.0)
            return if (cur >= amount) {
                val next = cur - amount
                balances[id] = next
                EconomyResponse(amount, next, EconomyResponse.ResponseType.SUCCESS, "")
            } else {
                EconomyResponse(amount, cur, EconomyResponse.ResponseType.FAILURE, "insufficient")
            }
        }

        // The following name-based methods delegate to OfflinePlayer variants
        override fun getBalance(playerName: String?): Double = getBalance(org.bukkit.Bukkit.getOfflinePlayer(playerName!!))
        override fun withdrawPlayer(playerName: String?, amount: Double): EconomyResponse = withdrawPlayer(org.bukkit.Bukkit.getOfflinePlayer(playerName!!), amount)
        override fun depositPlayer(playerName: String?, amount: Double): EconomyResponse = depositPlayer(org.bukkit.Bukkit.getOfflinePlayer(playerName!!), amount)

        // The rest banking-related APIs are unsupported in this fake
        override fun has(playerName: String?, amount: Double): Boolean = getBalance(playerName) >= amount
        override fun has(player: org.bukkit.OfflinePlayer?, amount: Double): Boolean = getBalance(player) >= amount
        override fun hasAccount(playerName: String?): Boolean = true
        override fun hasAccount(player: org.bukkit.OfflinePlayer?): Boolean = true
        override fun hasAccount(playerName: String?, worldName: String?): Boolean = true
        override fun hasAccount(player: org.bukkit.OfflinePlayer?, worldName: String?): Boolean = true
        override fun createPlayerAccount(playerName: String?): Boolean = true
        override fun createPlayerAccount(player: org.bukkit.OfflinePlayer?): Boolean = true
        override fun createPlayerAccount(playerName: String?, worldName: String?): Boolean = true
        override fun createPlayerAccount(player: org.bukkit.OfflinePlayer?, worldName: String?): Boolean = true

        // World-specific methods (unused)
        override fun getBalance(playerName: String?, world: String?): Double = getBalance(playerName)
        override fun getBalance(player: org.bukkit.OfflinePlayer?, world: String?): Double = getBalance(player)
        override fun has(playerName: String?, worldName: String?, amount: Double): Boolean = has(playerName, amount)
        override fun has(player: org.bukkit.OfflinePlayer?, worldName: String?, amount: Double): Boolean = has(player, amount)
        override fun withdrawPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(playerName, amount)
        override fun depositPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(playerName, amount)
        override fun withdrawPlayer(player: org.bukkit.OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = withdrawPlayer(player, amount)
        override fun depositPlayer(player: org.bukkit.OfflinePlayer?, worldName: String?, amount: Double): EconomyResponse = depositPlayer(player, amount)

        // Bank operations - unsupported in fake
        override fun createBank(name: String?, player: String?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun createBank(name: String?, player: org.bukkit.OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun deleteBank(name: String?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun bankBalance(name: String?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun bankHas(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun bankDeposit(name: String?, amount: Double): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun isBankOwner(name: String?, player: org.bukkit.OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun isBankMember(name: String?, playerName: String?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun isBankMember(name: String?, player: org.bukkit.OfflinePlayer?): EconomyResponse = EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "")
        override fun getBanks(): MutableList<String> = mutableListOf()
    }
}
