package red.man10.man10bank.service

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import org.junit.jupiter.api.*
import red.man10.man10bank.model.ResultCode
import java.math.BigDecimal
import red.man10.man10bank.testdoubles.FakeEconomy

class VaultEconomyServiceTest {

    private lateinit var server: ServerMock
    private lateinit var service: VaultEconomyService

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        service = VaultEconomyService(FakeEconomy())
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    @DisplayName("getBalance: 初期残高は0")
    fun getBalance_initiallyZero() {
        val p = server.addPlayer("VEconomyUser")
        val bal = service.getBalance(p.uniqueId)
        Assertions.assertEquals(BigDecimal.ZERO, bal)
    }

    @Test
    @DisplayName("deposit: 正常系（入金で残高が増える）")
    fun deposit_success_updatesBalance() {
        val p = server.addPlayer("VEDeposit")
        val res = service.deposit(p.uniqueId, BigDecimal(150))
        Assertions.assertEquals(ResultCode.SUCCESS, res.code)
        Assertions.assertEquals(BigDecimal(150), res.balance)
        Assertions.assertEquals(BigDecimal(150), service.getBalance(p.uniqueId))
    }

    @Test
    @DisplayName("deposit: 0以下の金額はINVALID_AMOUNT")
    fun deposit_invalidAmount_returnsInvalid() {
        val p = server.addPlayer("VEInvalidDeposit")
        val res0 = service.deposit(p.uniqueId, BigDecimal.ZERO)
        val resNeg = service.deposit(p.uniqueId, BigDecimal(-10))
        Assertions.assertEquals(ResultCode.INVALID_AMOUNT, res0.code)
        Assertions.assertEquals(ResultCode.INVALID_AMOUNT, resNeg.code)
    }

    @Test
    @DisplayName("deposit: オーバーフローはOVERFLOW")
    fun deposit_overflow_returnsOverflow() {
        val p = server.addPlayer("VEOverflow")
        val huge = BigDecimal.valueOf(10000000000000000)
        val res = service.deposit(p.uniqueId, huge)
        Assertions.assertEquals(ResultCode.OVERFLOW, res.code)
    }

    @Test
    @DisplayName("withdraw: 正常系（出金で残高が減る）")
    fun withdraw_success_updatesBalance() {
        val p = server.addPlayer("VEWithdraw")
        service.deposit(p.uniqueId, BigDecimal(300))
        val res = service.withdraw(p.uniqueId, BigDecimal(120))
        Assertions.assertEquals(ResultCode.SUCCESS, res.code)
        Assertions.assertEquals(BigDecimal(180), res.balance)
        Assertions.assertEquals(BigDecimal(180), service.getBalance(p.uniqueId))
    }

    @Test
    @DisplayName("withdraw: 残高不足はINSUFFICIENT_FUNDS")
    fun withdraw_insufficient_returnsInsufficient() {
        val p = server.addPlayer("VEInsufficient")
        val res = service.withdraw(p.uniqueId, BigDecimal(50))
        Assertions.assertEquals(ResultCode.INSUFFICIENT_FUNDS, res.code)
    }

    @Test
    @DisplayName("has: 判定が正しく機能する")
    fun has_worksCorrectly() {
        val p = server.addPlayer("VEHas")
        service.deposit(p.uniqueId, BigDecimal(200))
        Assertions.assertTrue(service.has(p.uniqueId, BigDecimal(150)))
        Assertions.assertTrue(service.has(p.uniqueId, BigDecimal.ZERO))
        Assertions.assertFalse(service.has(p.uniqueId, BigDecimal(250)))
    }

}
