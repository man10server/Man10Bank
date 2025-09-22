package red.man10.man10bank

import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.api.ChequesApiClient
import red.man10.man10bank.command.transaction.DepositCommand
import red.man10.man10bank.command.transaction.WithdrawCommand
import red.man10.man10bank.command.transaction.PayCommand
import red.man10.man10bank.command.balance.BalanceCommand
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.config.ConfigManager
import red.man10.man10bank.net.HttpClientFactory
import red.man10.man10bank.service.HealthService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.command.op.BankOpCommand
import red.man10.man10bank.command.atm.AtmCommand
import red.man10.man10bank.command.cheque.ChequeCommand
import red.man10.man10bank.ui.UIService
import red.man10.man10bank.service.ChequeService
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.command.serverloan.ServerLoanCommand
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.service.LoanService
import red.man10.man10bank.service.BankService

class Man10Bank : JavaPlugin(), Listener {

    // 設定/HTTPクライアント/スコープ
    private lateinit var configManager: ConfigManager
    private lateinit var httpClient: HttpClient
    private lateinit var scope: CoroutineScope

    // サービス
    private lateinit var healthService: HealthService
    private lateinit var vaultManager: red.man10.man10bank.service.VaultManager
    private lateinit var bankApi: BankApiClient
    private lateinit var atmApi: AtmApiClient
    private lateinit var chequesApi: ChequesApiClient
    private lateinit var serverLoanApi: ServerLoanApiClient
    private lateinit var cashItemManager: CashItemManager
    private lateinit var cashExchangeService: CashExchangeService
    private lateinit var uiService: UIService
    private lateinit var chequeService: ChequeService
    private lateinit var serverLoanService: ServerLoanService
    private lateinit var loanApi: LoanApiClient
    private lateinit var loanService: LoanService
    private lateinit var bankService: BankService

    // サーバー識別名（configの serverName が空/未設定の場合はBukkitのサーバー名を使用）
    lateinit var serverName: String
        private set

    override fun onEnable() {
        // 初期化フロー
        configManager = ConfigManager(this)
        val apiConfig = loadApiConfigOrDisable() ?: return
        initRuntime(apiConfig)
        initServerName()
        initServices()
        registerCommands()
        registerEvents()
        registerProviders()
        runStartupHealthCheck()
    }

    override fun onDisable() {
        // スコープとクライアントをクリーンアップ
        if (this::scope.isInitialized) scope.cancel()
        if (this::httpClient.isInitialized) httpClient.close()
    }

    // 初期化ヘルパー
    private fun loadApiConfigOrDisable(): ConfigManager.ApiConfig? {
        return try {
            configManager.load()
        } catch (e: Exception) {
            logger.severe("config.yml の読み込みに失敗しました: ${e.message}")
            server.pluginManager.disablePlugin(this)
            null
        }
    }

    private fun initRuntime(apiConfig: ConfigManager.ApiConfig) {
        httpClient = HttpClientFactory.create(apiConfig)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun initServices() {
        healthService = HealthService(HealthApiClient(httpClient))
        bankApi = BankApiClient(httpClient)
        atmApi = AtmApiClient(httpClient)
        chequesApi = ChequesApiClient(httpClient)
        serverLoanApi = ServerLoanApiClient(httpClient)
        loanApi = LoanApiClient(httpClient)
        vaultManager = red.man10.man10bank.service.VaultManager(this)
        cashItemManager = CashItemManager(this)
        chequeService = ChequeService(this, scope, chequesApi)
        serverLoanService = ServerLoanService(this, serverLoanApi)
        loanService = LoanService(this, loanApi)
        bankService = BankService(this, bankApi, vaultManager)
        uiService = UIService(this)

        // 起動時に現金アイテム設定を読み込む
        val loadedCash = cashItemManager.load()
        if (loadedCash.isNotEmpty()) {
            logger.info("現金アイテム設定を ${loadedCash.size} 件読み込みました。")
        }
        val hooked = vaultManager.hook()
        if (!hooked) {
            logger.warning("Vault(Economy) が見つかりません。経済連携機能は無効です。")
        } else {
            logger.info("Vault(Economy) に接続しました: ${vaultManager.provider()?.name}")
        }
        cashExchangeService = CashExchangeService(this, scope, atmApi, vaultManager, cashItemManager)
    }

    private fun initServerName() {
        val cfg = config.getString("serverName")?.trim().orEmpty()
        serverName = cfg.ifBlank { server.name }
        logger.info("ServerName: $serverName")
    }

    private fun runStartupHealthCheck() {
        scope.launch {
            val msg = healthService.buildHealthMessage()
            logger.info(msg)
        }
    }

    private fun registerCommands() {
        getCommand("deposit")?.setExecutor(DepositCommand(this, scope, bankService))
        getCommand("withdraw")?.setExecutor(WithdrawCommand(this, scope, bankService))
        getCommand("mpay")?.setExecutor(PayCommand(this, scope, bankApi))
        getCommand("bankop")?.setExecutor(BankOpCommand(this, scope, healthService, cashItemManager))
        getCommand("atm")?.setExecutor(AtmCommand(this, scope, atmApi, vaultManager, cashItemManager, cashExchangeService))
        getCommand("mcheque")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mchequeop")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mrevo")?.setExecutor(ServerLoanCommand(this, scope, serverLoanService))

        // 残高系（/bal, /balance ほか別名にも割り当て）
        listOf("bal", "balance", "money", "bank").forEach { cmd ->
            getCommand(cmd)?.setExecutor(BalanceCommand(this, scope))
        }
    }

    private fun registerEvents() {
        // GUIのイベントをハンドル
        server.pluginManager.registerEvents(uiService, this)
        server.pluginManager.registerEvents(chequeService, this)
        server.pluginManager.registerEvents(loanService, this)
    }

    private fun registerProviders() {
        // デフォルトの表示プロバイダを登録（実装側で登録）
        cashItemManager.registerBalanceProvider()
        vaultManager.registerBalanceProvider()
        bankService.registerBalanceProvider()
    }
}
