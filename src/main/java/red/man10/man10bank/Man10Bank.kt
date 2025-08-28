package red.man10.man10bank

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.service.BankService
import red.man10.man10bank.commands.DepositCommand
import red.man10.man10bank.commands.MpayCommand
import red.man10.man10bank.commands.WithdrawCommand
import red.man10.man10bank.db.DatabaseProvider
import red.man10.man10bank.service.VaultEconomyService

class Man10Bank : JavaPlugin(), Listener {

    lateinit var appScope: CoroutineScope
    lateinit var bankService: BankService
    lateinit var vault: VaultEconomyService

    override fun onEnable() {
        saveDefaultConfig()
        DatabaseProvider.init(this)
        logger.info("Man10Bank を有効化しました。DB初期化: ${DatabaseProvider.isInitialized()}")

        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        bankService = BankService(DatabaseProvider.database(), this)
        val vault = VaultEconomyService.create()
        if (vault == null) {
            logger.warning("Vault の Economy プラグインが見つかりません。ホワイトリストにします。")
            Bukkit.setWhitelist(true)
        } else {
            this.vault = vault
        }

        getCommand("deposit")?.setExecutor(DepositCommand(this))
        getCommand("withdraw")?.setExecutor(WithdrawCommand(this))
        getCommand("mpay")?.setExecutor(MpayCommand(this))

    }

    override fun onDisable() {
        if (this::appScope.isInitialized) {
            appScope.cancel()
        }
        bankService.shutdown()
        logger.info("Man10Bank を無効化しました。")
    }
}
