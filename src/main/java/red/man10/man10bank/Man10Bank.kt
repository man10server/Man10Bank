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
import red.man10.man10bank.command.transaction.VaultPayCommand
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.economy.Man10Economy
import red.man10.man10bank.listener.VaultLifecycleListener
import red.man10.man10bank.service.*
import red.man10.man10bank.service.vault.VaultCache
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.service.vault.VaultSyncClient
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

    // 電子マネー(Vault Provider)スタック
    private lateinit var vaultCache: VaultCache
    private lateinit var vaultApi: VaultApiClient
    private lateinit var vaultService: VaultService
    private lateinit var vaultSync: VaultSyncClient
    private lateinit var man10Economy: Man10Economy
    private lateinit var vaultConfig: ConfigManager.VaultConfig
    private var vaultProviderRegistered: Boolean = false

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
        registerVaultProvider()
        registerProviders()
        runStartupHealthCheck()
    }

    override fun onDisable() {
        // Vault(Economy) Provider 登録を解除（登録していた場合のみ）。
        if (vaultProviderRegistered && this::man10Economy.isInitialized) {
            server.servicesManager.unregister(Economy::class.java, man10Economy)
        }
        // スコープとクライアントをクリーンアップ（同期WebSocketのループも scope.cancel で停止する）。
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

        // 電子マネー(Vault Provider)スタックを構築する。
        vaultConfig = configManager.loadVaultConfig()
        vaultCache = VaultCache()
        vaultApi = VaultApiClient(httpClient)
        vaultService = VaultService(this, serverName, scope, vaultApi, vaultCache)
        man10Economy = Man10Economy(this, vaultService, vaultConfig.currencyNameSingular, vaultConfig.currencyNamePlural)
        vaultSync = VaultSyncClient(this, scope, httpClient, vaultService, apiConfig.baseUrl, serverName)

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
        getCommand("mpay")?.setExecutor(PayCommand(this, scope, bankService))
        getCommand("ballog")?.setExecutor(BalLogCommand(scope, bankService))
        getCommand("mbaltop")?.setExecutor(red.man10.man10bank.command.balance.BalTopCommand(this, scope, estateService, serverEstateService))
        getCommand("bankop")?.setExecutor(BankOpCommand(this, scope, healthService, cashItemManager, estateService, featureToggles, bankService, serverLoanService, vaultService))
        getCommand("atm")?.setExecutor(AtmCommand(this, scope, atmService, vaultManager, cashItemManager, featureToggles))
        getCommand("mcheque")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mchequeop")?.setExecutor(ChequeCommand(this, scope, chequeService))
        getCommand("mrevo")?.setExecutor(ServerLoanCommand(this, scope, serverLoanService))
        getCommand("mlend")?.setExecutor(red.man10.man10bank.command.loan.LendCommand(this, scope, loanService, featureToggles))
        // 電子マネー送金 /pay（同一サーバー在席者のみ）
        getCommand("pay")?.setExecutor(VaultPayCommand(this, scope, vaultService))

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
        // 電子マネーキャッシュの join/quit ライフサイクル
        server.pluginManager.registerEvents(VaultLifecycleListener(scope, vaultService, vaultSync), this)
    }

    /**
     * Man10Bank を Vault(Economy) Provider として登録する（VaultProvider 8.3）。
     * - vault.providerEnabled=false の間は登録もフェイルセーフも行わない（段階導入/ロールバック）。
     * - 登録後に実効 Provider が自分自身であることを検証する。
     *   検証失敗（競合/例外/Vault不在）時は severe ログを出し、サーバーをホワイトリスト化して
     *   新規参加を遮断する安全弁を作動させる（誤った Economy 下での取引による整合性崩壊を防ぐ）。
     * - 登録成功時は同期 WebSocket を開始し、VaultManager を Man10Economy へ張り直す
     *   （内部利用者も単一の真実=user_vault を参照させる）。
     */
    private fun registerVaultProvider() {
        if (!vaultConfig.providerEnabled) {
            logger.info("vault.providerEnabled=false のため Vault(Economy) Provider 登録をスキップします。")
            return
        }

        if (server.pluginManager.getPlugin("Vault") == null) {
            failVaultRegistration("Vault プラグインが見つかりません（不在）。")
            return
        }

        try {
            server.servicesManager.register(Economy::class.java, man10Economy, this, ServicePriority.High)
        } catch (t: Throwable) {
            failVaultRegistration("Provider 登録時に例外が発生しました: ${t.message}")
            return
        }

        // 実効 Provider が自分自身であることを検証する（競合検知）。
        val effective = server.servicesManager.getRegistration(Economy::class.java)?.provider
        if (effective !== man10Economy) {
            failVaultRegistration("実効 Economy Provider が Man10Bank ではありません（競合相手: ${effective?.name}）。")
            return
        }

        vaultProviderRegistered = true
        logger.info("Man10Bank を Vault(Economy) Provider として登録しました。")

        // 内部利用者(VaultManager 経由)も Man10Economy=user_vault を参照させる。
        vaultManager.hook()

        // 同期 WebSocket を開始（presence + push 受信、切断時は自動再接続）。
        vaultSync.start()
    }

    /** Vault Provider 登録失敗時の安全弁: severe ログ＋ホワイトリスト化で新規参加を遮断する。 */
    private fun failVaultRegistration(detail: String) {
        logger.severe(
            "Vault(Economy) Provider 登録に失敗しました: $detail " +
                "誤った Economy 下での取引で電子マネー整合性が崩れるのを防ぐため、" +
                "サーバーをホワイトリスト化して新規参加を遮断します。原因解消後に解除してください。"
        )
        server.setWhitelist(true)
    }

    private fun registerProviders() {
        // デフォルトの表示プロバイダを登録（実装側で登録）
        cashItemManager.registerBalanceProvider()
        vaultManager.registerBalanceProvider()
        bankService.registerBalanceProvider()
        serverLoanService.registerBalanceProvider()
    }
}
