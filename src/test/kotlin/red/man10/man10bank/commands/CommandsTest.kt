package red.man10.man10bank.commands

import be.seeseemelk.mockbukkit.MockBukkit
import be.seeseemelk.mockbukkit.ServerMock
import org.junit.jupiter.api.*
import red.man10.man10bank.Man10Bank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import red.man10.man10bank.service.VaultEconomyService
import red.man10.man10bank.service.BankService
import org.ktorm.database.Database
import red.man10.man10bank.testdoubles.FakeEconomy
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("MockBukkit で Man10Bank をロードするには jar ロード or 非final化が必要。環境整備後に有効化してください。")
class CommandsTest {

    private lateinit var server: ServerMock
    private lateinit var plugin: Man10Bank

    @BeforeAll
    fun boot() {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(Man10Bank::class.java)
        // Inject services
        val db = Database.connect(
            url = "jdbc:h2:mem:cmdtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        db.useConnection { c ->
            c.createStatement().use { st ->
                st.addBatch(
                    """
                    CREATE TABLE IF NOT EXISTS user_bank (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      player VARCHAR(16),
                      uuid VARCHAR(36),
                      balance DECIMAL
                    );
                    """.trimIndent()
                )
                st.addBatch(
                    """
                    CREATE TABLE IF NOT EXISTS money_log (
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
            }
        }
        plugin.appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        plugin.bankService = BankService(db, "TestBank")
        // Inject fake Vault economy (plugin.vault is set only when Vault is present)
        plugin.vault = VaultEconomyService(FakeEconomy())

        // Ensure plugin.yml commands are bound to executors
        server.getPluginCommand("deposit")?.setExecutor(DepositCommand(plugin))
        server.getPluginCommand("withdraw")?.setExecutor(WithdrawCommand(plugin))
        server.getPluginCommand("mpay")?.setExecutor(MpayCommand(plugin))
    }

    @AfterAll
    fun teardown() {
        MockBukkit.unmock()
    }

    @Test
    @DisplayName("/deposit: 所持金から銀行へ入金できる")
    fun deposit_command_deposits_to_bank() {
        val p = server.addPlayer("CmdDeposit")
        val uuid = p.uniqueId
        // Seed vault balance
        plugin.vault.deposit(uuid, BigDecimal(500))

        Assertions.assertTrue(server.dispatchCommand(p, "deposit 200"))

        // Allow scheduled tasks to run
        server.scheduler.performTicks(10L)

        // Bank balance should reflect deposit
        val bal = runBlockingGetBalance(uuid)
        Assertions.assertEquals(BigDecimal(200), bal)
    }

    @Test
    @DisplayName("/withdraw: 銀行から所持金へ出金できる")
    fun withdraw_command_withdraws_from_bank() {
        val p = server.addPlayer("CmdWithdraw")
        val uuid = p.uniqueId
        // Seed bank balance
        runBlockingDeposit(uuid, BigDecimal(300))

        Assertions.assertTrue(server.dispatchCommand(p, "withdraw 120"))
        server.scheduler.performTicks(10L)

        val bankBal = runBlockingGetBalance(uuid)
        Assertions.assertEquals(BigDecimal(180), bankBal)

        val vaultBal = plugin.vault.getBalance(uuid)
        Assertions.assertEquals(BigDecimal(120), vaultBal)
    }

    @Test
    @DisplayName("/mpay: プレイヤー間の送金ができる")
    fun mpay_command_transfers_between_players() {
        val from = server.addPlayer("CmdFrom")
        val to = server.addPlayer("CmdTo")
        runBlockingDeposit(from.uniqueId, BigDecimal(1000))

        Assertions.assertTrue(server.dispatchCommand(from, "mpay ${to.name} 350"))
        server.scheduler.performTicks(10L)

        val fromBal = runBlockingGetBalance(from.uniqueId)
        val toBal = runBlockingGetBalance(to.uniqueId)
        Assertions.assertEquals(BigDecimal(650), fromBal)
        Assertions.assertEquals(BigDecimal(350), toBal)
    }

    // ---- helpers: run bank operations synchronously in tests ----
    private fun runBlockingGetBalance(uuid: java.util.UUID): BigDecimal? =
        kotlinx.coroutines.runBlocking { plugin.bankService.getBalance(uuid) }

    private fun runBlockingDeposit(uuid: java.util.UUID, amount: BigDecimal) =
        kotlinx.coroutines.runBlocking { plugin.bankService.deposit(uuid, amount, "Seed", null) }
}
