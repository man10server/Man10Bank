package red.man10.man10bank

import kotlinx.coroutines.CoroutineScope
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.service.BankService
import red.man10.man10bank.service.VaultEconomyService

// Test-double friendly plugin class. Open to allow MockBukkit proxying.
open class Man10Bank : JavaPlugin(), Listener {
    lateinit var appScope: CoroutineScope
    lateinit var bankService: BankService
    lateinit var vault: VaultEconomyService
}

