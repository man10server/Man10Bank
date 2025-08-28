package red.man10.man10bank.bank.service

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.ktorm.database.Database
import red.man10.man10bank.service.BankService
import red.man10.man10bank.model.ResultCode

class BankServiceTest {

    private lateinit var db: Database
    private lateinit var service: BankService
    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()

        db = Database.connect(
            url = "jdbc:h2:mem:bank;MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        // 最小限のテーブルを作成
        db.useConnection { c ->
            val st = c.createStatement()
            st.addBatch(
                """
                CREATE TABLE user_bank (
                  id INT AUTO_INCREMENT PRIMARY KEY,
                  player VARCHAR(16),
                  uuid VARCHAR(36),
                  balance DECIMAL
                );
                """.trimIndent()
            )
            st.addBatch(
                """
                CREATE TABLE money_log (
                  id INT AUTO_INCREMENT PRIMARY KEY,
                  player VARCHAR(16),
                  uuid VARCHAR(36),
                  plugin_name VARCHAR(16),
                  amount DECIMAL,
                  note VARCHAR(64),
                  display_note VARCHAR(64),
                  server VARCHAR(16),
                  deposit BOOLEAN,
                  date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
                """.trimIndent()
            )
            st.executeBatch()
            st.close()
        }
        service = BankService(db, "TestBank", serverName = "test")
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
        service.shutdown()
        db.useConnection { it.createStatement().use { st -> st.execute("DROP ALL OBJECTS") } }
    }

    @Test
    @DisplayName("setBalance: 正常系増額（残高が指定額に設定される）")
    fun setBalance_success_updatesBalance() = runBlocking {
        try {
            val player = server.addPlayer("Grace")
            val uuid = player.uniqueId
            val res = service.setBalance(uuid, 1000.toBigDecimal(), "Set", null)
            assertEquals(ResultCode.SUCCESS, res.code)
            assertEquals(1000.toBigDecimal(), res.balance)
            val bal = service.getBalance(uuid)
            assertEquals(1000.toBigDecimal(), bal)
        } finally {
            MockBukkit.unmock()
        }
    }

    @Test
    @DisplayName("setBalance: 正常系減額（残高が指定額に設定される）")
    fun setBalance_decrease_updatesBalance() = runBlocking {
        val player = server.addPlayer("Henry")
        val uuid = player.uniqueId
        val dep = service.deposit(uuid, 800.toBigDecimal(), "Deposit", null)
        assertEquals(ResultCode.SUCCESS, dep.code)
        val res = service.setBalance(uuid, 300.toBigDecimal(), "Set", null)
        assertEquals(ResultCode.SUCCESS, res.code)
        assertEquals(300.toBigDecimal(), res.balance)
        val bal = service.getBalance(uuid)
        assertEquals(300.toBigDecimal(), bal)
    }

    @Test
    @DisplayName("setBalance: 負の値はINVALID_AMOUNT")
    fun setBalance_negative_returnsInvalid() = runBlocking {
        val player = server.addPlayer("Hank")
        val uuid = player.uniqueId
        val res = service.setBalance(uuid, (-500).toBigDecimal(), "Set", null)
        assertEquals(ResultCode.INVALID_AMOUNT, res.code)
    }

    @Test
    @DisplayName("getBalance: 未登録UUIDはnullを返す")
    fun getBalance_returnsNull_whenUnknown() = runBlocking {
        val player = server.addPlayer("Alice")
        val bal = service.getBalance(player.uniqueId)
        assertNull(bal)
    }

    @Test
    @DisplayName("getBalance: 登録済UUIDは残高を返す")
    fun getBalance_returnsBalance_whenKnown() = runBlocking {
        val player = server.addPlayer("Bob")
        val uuid = player.uniqueId
        val dep = service.deposit(uuid, 200.toBigDecimal(), "Deposit", null)
        assertEquals(ResultCode.SUCCESS, dep.code)
        val bal = service.getBalance(uuid)
        assertEquals(200.toBigDecimal(), bal)
    }

    @Test
    @DisplayName("deposit: 正常系（入金成功で残高が増える）")
    fun deposit_success_returnsNewBalance() = runBlocking {
        val player = server.addPlayer("Eve")
        val uuid = player.uniqueId
        val res = service.deposit(uuid, 150.toBigDecimal(), "Deposit", null)
        assertEquals(ResultCode.SUCCESS, res.code)
        assertEquals(150.toBigDecimal(), res.balance)
        val bal = service.getBalance(uuid)
        assertEquals(150.toBigDecimal(), bal)
        val log = service.getLog(uuid, 1, 0)
        assertEquals("Deposit", log[0].note)
    }

    @Test
    @DisplayName("deposit: 0以下の金額はINVALID_AMOUNT")
    fun deposit_invalidAmount_returnsInvalid() = runBlocking {
        val player = server.addPlayer("Charlie")
        val uuid = player.uniqueId
        val res0 = service.deposit(uuid, 0.toBigDecimal(), "Deposit", null)
        val resNeg = service.deposit(uuid, (-10).toBigDecimal(), "Deposit", null)
        assertEquals(ResultCode.INVALID_AMOUNT, res0.code)
        assertEquals(ResultCode.INVALID_AMOUNT, resNeg.code)
    }

    @Test
    @DisplayName("withdraw: 正常系（出金成功で残高が減る）")
    fun withdraw_success_returnsNewBalance() = runBlocking {
        val player = server.addPlayer("Frank")
        val uuid = player.uniqueId
        val dep = service.deposit(uuid, 500.toBigDecimal(), "Deposit", null)
        assertEquals(ResultCode.SUCCESS, dep.code)
        val res = service.withdraw(uuid, 200.toBigDecimal(), "Withdraw", null)
        assertEquals(ResultCode.SUCCESS, res.code)
        assertEquals(300.toBigDecimal(), res.balance)
        val bal = service.getBalance(uuid)
        assertEquals(300.toBigDecimal(), bal)
        val log = service.getLog(uuid, 1, 0)
        assertEquals("Withdraw", log[0].note)
    }

    @Test
    @DisplayName("withdraw: 残高不足はINSUFFICIENT_FUNDS")
    fun withdraw_insufficientFunds_withMock_returnsInsufficient() = runBlocking {
        val player = server.addPlayer("Alice")
        val uuid = player.uniqueId
        val res = service.withdraw(uuid, 100.toBigDecimal(), "Withdraw", null)
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, res.code)
    }

    @Test
    @DisplayName("withdraw: 0以下の金額はINVALID_AMOUNT")
    fun withdraw_invalidAmount_returnsInvalid() = runBlocking {
        val player = server.addPlayer("Dave")
        val uuid = player.uniqueId
        val res0 = service.withdraw(uuid, 0.toBigDecimal(), "Withdraw", null)
        val resNeg = service.withdraw(uuid, (-10).toBigDecimal(), "Withdraw", null)
        assertEquals(ResultCode.INVALID_AMOUNT, res0.code)
        assertEquals(ResultCode.INVALID_AMOUNT, resNeg.code)
    }

    @Test
    @DisplayName("transfer: 正常系（送金成功でfrom残高が減る)")
    fun transfer_success_withMock_updatesBalances() = runBlocking {
        val from = server.addPlayer("FromUser")
        val to = server.addPlayer("ToUser")
        val fromId = from.uniqueId
        val toId = to.uniqueId

        val dep = service.deposit(fromId, 1000.toBigDecimal(),  "Deposit", null)
        assertEquals(ResultCode.SUCCESS, dep.code)

        val res = service.transfer(fromId, toId, 300.toBigDecimal())
        assertEquals(ResultCode.SUCCESS, res.code)

        val fromBal = service.getBalance(fromId)
        val toBal = service.getBalance(toId)
        assertEquals(700.toBigDecimal(), fromBal)
        assertEquals(300.toBigDecimal(), toBal)

        val fromLog = service.getLog(fromId, 1, 0)
        val toLog = service.getLog(toId, 1, 0)
        assertEquals("Transfer to ${to.name}", fromLog[0].note)
        assertEquals("Transfer from ${from.name}", toLog[0].note)
    }

    @Test
    @DisplayName("transfer: 0以下の金額はINVALID_AMOUNT")
    fun transfer_invalidAmount_returnsInvalid() = runBlocking {
        val player1 = server.addPlayer("Ivy")
        val player2 = server.addPlayer("Jack")
        val from = player1.uniqueId
        val to = player2.uniqueId
        val res0 = service.transfer(from, to, 0.toBigDecimal())
        val resNeg = service.transfer(from, to, (-20).toBigDecimal())
        assertEquals(ResultCode.INVALID_AMOUNT, res0.code)
        assertEquals(ResultCode.INVALID_AMOUNT, resNeg.code)
    }

    @Test
    @DisplayName("transfer: 残高不足はINSUFFICIENT_FUNDS")
    fun transfer_insufficientFunds_withMock_returnsInsufficient() = runBlocking {
        val player1 = server.addPlayer("Kathy")
        val player2 = server.addPlayer("Leo")
        val from = player1.uniqueId
        val to = player2.uniqueId
        val res = service.transfer(from, to, 100.toBigDecimal())
        assertEquals(ResultCode.INSUFFICIENT_FUNDS, res.code)
    }
}
