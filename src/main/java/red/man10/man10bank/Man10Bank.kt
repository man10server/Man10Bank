package red.man10.man10bank

import io.ktor.client.*
import kotlinx.coroutines.CoroutineExceptionHandler
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
import red.man10.man10bank.config.ConfigManager
import red.man10.man10bank.net.HttpClientFactory
import red.man10.man10bank.command.op.BankOpCommand
import red.man10.man10bank.command.atm.AtmCommand
import red.man10.man10bank.command.cheque.ChequeCommand
import red.man10.man10bank.ui.UIService
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.command.serverloan.ServerLoanCommand
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.command.transaction.BalLogCommand
import red.man10.man10bank.service.*
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.economy.Man10BankProvider
import red.man10.man10bank.listener.VaultLifecycleListener
import red.man10.man10bank.command.transaction.VaultPayCommand
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.ServicePriority

class Man10Bank : JavaPlugin(), Listener {

    // 設定/HTTPクライアント/スコープ
    private lateinit var configManager: ConfigManager
    private lateinit var httpClient: HttpClient
    private lateinit var scope: CoroutineScope

    // サービス
    private lateinit var healthService: HealthService
    private lateinit var vaultManager: VaultManager
    private lateinit var bankApi: BankApiClient
    private lateinit var atmApi: AtmApiClient
    private lateinit var chequesApi: ChequesApiClient
    private lateinit var serverLoanApi: ServerLoanApiClient
    private lateinit var serverEstateApi: red.man10.man10bank.api.ServerEstateApiClient
    private lateinit var estateApi: red.man10.man10bank.api.EstateApiClient
    private lateinit var cashItemManager: CashItemManager
    private lateinit var atmService: AtmService
    private lateinit var uiService: UIService
    private lateinit var chequeService: ChequeService
    private lateinit var estateService: EstateService
    private lateinit var serverLoanService: ServerLoanService
    private lateinit var serverEstateService: ServerEstateService
    private lateinit var loanApi: LoanApiClient
    private lateinit var loanService: LoanService
    private lateinit var bankService: BankService
    private lateinit var featureToggles: FeatureToggleService

    // 電子マネー(Vault Provider)関連
    private lateinit var vaultApi: VaultApiClient
    private lateinit var vaultService: VaultService
    private var man10BankProvider: Man10BankProvider? = null

    // サーバー識別名（configの serverName が空/未設定の場合はBukkitのサーバー名を使用）
    lateinit var serverName: String
        private set

    /**
     * 互換用 BankAPI 等から共有 BankApiClient を再利用するための公開アクセサ。
     * - 本体が保持する単一の HttpClient を使う BankApiClient を返す。
     * - 初期化前（onEnable 未完了）は null を返す。
     * - 独自に HttpClient を生成させないことでコネクション/スレッドプールのリークを防ぐ。
     */
    val sharedBankApiClient: BankApiClient?
        get() = if (this::bankApi.isInitialized) bankApi else null

    override fun onEnable() {
        // 初期化フロー
        configManager = ConfigManager(this)
        val apiConfig = loadApiConfigOrDisable() ?: return
        initRuntime(apiConfig)
        initServerName()
        initServices(apiConfig)
        registerCommands()
        registerEvents()
        registerProviders()
        runStartupHealthCheck()
    }

    override fun onDisable() {
        // 電子マネー: バックグラウンド停止 + Economy 登録解除
        if (this::vaultService.isInitialized) vaultService.shutdown()
        man10BankProvider?.let { server.servicesManager.unregister(Economy::class.java, it) }
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
        // 未捕捉例外（runCatching外のlaunch等）を握り潰さずログへ残す（DESIGN 3.5）。
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.severe("コルーチンで未捕捉の例外が発生しました: ${throwable.message}")
            throwable.printStackTrace()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    }

    private fun initServices(apiConfig: ConfigManager.ApiConfig) {
        healthService = HealthService(HealthApiClient(httpClient), apiConfig)
        bankApi = BankApiClient(httpClient)
        atmApi = AtmApiClient(httpClient)
        chequesApi = ChequesApiClient(httpClient)
        serverLoanApi = ServerLoanApiClient(httpClient)
        serverEstateApi = red.man10.man10bank.api.ServerEstateApiClient(httpClient)
        estateApi = red.man10.man10bank.api.EstateApiClient(httpClient)
        loanApi = LoanApiClient(httpClient)
        vaultApi = VaultApiClient(httpClient)

        vaultManager = VaultManager(this)
        cashItemManager = CashItemManager(this)
        featureToggles = FeatureToggleService(this)

        chequeService = ChequeService(this, scope, chequesApi, featureToggles)
        serverLoanService = ServerLoanService(this, serverLoanApi, featureToggles)
        serverEstateService = ServerEstateService(this, serverEstateApi)
        estateService = EstateService(this, scope, estateApi, vaultManager, cashItemManager, chequeService)
        loanService = LoanService(this, scope, loanApi, featureToggles)
        bankService = BankService(this, bankApi, vaultManager, featureToggles)
        uiService = UIService(this)
        atmService = AtmService(this, scope, atmApi, vaultManager, cashItemManager)

        // 電子マネー(Vault Provider)スタックを初期化する。
        val vaultConfig = configManager.loadVaultConfig()
        vaultService = VaultService(this, scope, vaultApi, serverName, vaultConfig)
        vaultManager.attach(vaultService, scope)
        // Vault 本体がある場合のみ Economy 実装クラスをロード/生成する(無い環境での NoClassDefFoundError 回避)。
        man10BankProvider = if (server.pluginManager.getPlugin("Vault") != null) {
            Man10BankProvider(this, vaultService)
        } else {
            null
        }

        // 起動時に現金アイテム設定を読み込む
        val loadedCash = cashItemManager.load()
        if (loadedCash.isNotEmpty()) {
            logger.info("現金アイテム設定を ${loadedCash.size} 件読み込みました。")
        }
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
        getCommand("deposit")?.setExecutor(DepositCommand(this, scope, vaultService))
        getCommand("withdraw")?.setExecutor(WithdrawCommand(this, scope, bankService, vaultService))
        getCommand("mpay")?.setExecutor(PayCommand(this, scope, bankService))
        getCommand("pay")?.setExecutor(VaultPayCommand(this, scope, vaultService))
        getCommand("ballog")?.setExecutor(BalLogCommand(scope, bankService))
        getCommand("mbaltop")?.setExecutor(red.man10.man10bank.command.balance.BalTopCommand(this, scope, estateService, serverEstateService))
        getCommand("bankop")?.setExecutor(BankOpCommand(this, scope, healthService, cashItemManager, estateService, featureToggles, bankService, serverLoanService, vaultManager))
        getCommand("atm")?.setExecutor(AtmCommand(this, scope, atmService, vaultManager, cashItemManager, featureToggles))
        getCommand("mcheque")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mchequeop")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mrevo")?.setExecutor(ServerLoanCommand(this, scope, serverLoanService))
        getCommand("mlend")?.setExecutor(red.man10.man10bank.command.loan.LendCommand(this, scope, loanService, featureToggles))

        // 残高系（/bal, /balance ほか別名にも割り当て）
        // Bukkit/Vault 依存値はメインスレッドで先に収集するため Vault/現金マネージャを渡す（DESIGN 3.5）。
        listOf("mbal", "bal", "balance", "money", "bank").forEach { cmd ->
            getCommand(cmd)?.setExecutor(BalanceCommand(this, scope, vaultManager, cashItemManager))
        }
    }

    private fun registerEvents() {
        // GUIのイベントをハンドル
        server.pluginManager.registerEvents(uiService, this)
        server.pluginManager.registerEvents(chequeService, this)
        server.pluginManager.registerEvents(loanService, this)
        server.pluginManager.registerEvents(estateService, this)
        server.pluginManager.registerEvents(cashItemManager, this)
        server.pluginManager.registerEvents(VaultLifecycleListener(vaultService), this)
    }

    private fun registerProviders() {
        // デフォルトの表示プロバイダを登録（実装側で登録）
        cashItemManager.registerBalanceProvider()
        vaultManager.registerBalanceProvider()
        bankService.registerBalanceProvider()
        serverLoanService.registerBalanceProvider()
        registerVaultProvider()
    }

    /**
     * Man10Bank を Vault(Economy) Provider として ServicesManager に登録し、
     * VaultService のバックグラウンド処理を開始する(設計書 §10.1)。
     * Vault 本体が無い場合は Economy 登録のみスキップし、内製 API 経路(コマンド)は引き続き有効。
     */
    private fun registerVaultProvider() {
        val vaultConfig = configManager.loadVaultConfig()
        val provider = man10BankProvider
        if (vaultConfig.providerEnabled && provider != null) {
            server.servicesManager.register(Economy::class.java, provider, this, ServicePriority.High)
            val effective = server.servicesManager.getRegistration(Economy::class.java)?.provider
            if (effective === provider) {
                vaultService.registered = true
                logger.info("Vault(Economy) Provider として登録しました(電子マネー)。")
            } else {
                logger.severe(
                    "別の Economy Provider が実効になっています(${effective?.javaClass?.name})。Vault Provider 機能を停止します。"
                )
                vaultService.setDisabled(true)
            }
        } else if (provider == null) {
            logger.warning("Vault が見つからないため Economy Provider 登録をスキップします(内製 API 経路は有効)。")
        } else {
            logger.info("vault.providerEnabled=false のため Economy Provider 登録をスキップします。")
        }

        // 既知の許容リスク(設計書 §15.1)を起動時に通知する。
        logger.warning(
            "[vault] 送信待ちキューはメモリ保持です。Paper 強制終了時、SUCCESS 返却済みで未送信の Provider 操作は失われ得ます(既知の許容リスク)。"
        )

        // バックグラウンド処理(キュー送信/健全性監視/定期再同期)を開始し、既存オンラインプレイヤーをロードする。
        vaultService.start()
        server.onlinePlayers.forEach { vaultService.onJoin(it.uniqueId) }
    }
}
