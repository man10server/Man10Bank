package red.man10.man10bank

import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.config.ConfigManager
import red.man10.man10bank.net.HttpClientFactory
import java.util.*

/**
 * 旧バージョン互換のBankAPI。
 * - 既存プラグインの呼び出しシグネチャを保持しつつ、内部ではWebAPIクライアントへ委譲します。
 * - BankService側の修正は行いません。ロケータも使用しません。
 */
class BankAPI(private val plugin: JavaPlugin) {

    private val man10Bank: Man10Bank?
        get() = Bukkit.getPluginManager().getPlugin("Man10Bank") as? Man10Bank

    private val apiClient: BankApiClient? by lazy {
        val core = man10Bank ?: return@lazy null
        val cfg = ConfigManager(core).load()
        val http = HttpClientFactory.create(cfg)
        BankApiClient(http)
    }

    private fun serverName(): String = man10Bank?.serverName ?: plugin.server.name

    // ============ 同期API（互換） ============

    fun withdraw(uuid: UUID, amount: Double, note: String, displayNote: String): Boolean {
        val api = apiClient ?: return false
        val req = WithdrawRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = serverName(),
        )
        val result = runBlocking { api.withdraw(req) }
        return result.isSuccess
    }

    @Deprecated(
        message = "displayNoteが設定できない",
        replaceWith = ReplaceWith("withdraw(uuid, amount, note, note)"),
        level = DeprecationLevel.WARNING,
    )
    fun withdraw(uuid: UUID, amount: Double, note: String): Boolean =
        withdraw(uuid, amount, note, note)

    fun deposit(uuid: UUID, amount: Double, note: String, displayNote: String) {
        val api = apiClient ?: return
        val req = DepositRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = serverName(),
        )
        runBlocking { api.deposit(req) }
    }

    @Deprecated(
        message = "displayNoteが設定できない",
        replaceWith = ReplaceWith("deposit(uuid, amount, note, note)"),
        level = DeprecationLevel.WARNING,
    )
    fun deposit(uuid: UUID, amount: Double, note: String) =
        deposit(uuid, amount, note, note)

    fun getBalance(uuid: UUID): Double = runBlocking {
        val api = apiClient ?: return@runBlocking 0.0
        api.getBalance(uuid).getOrElse { 0.0 }
    }

    // ============ 非同期API（互換） ============

    fun asyncDeposit(uuid: UUID, amount: Double, note: String, displayNote: String, callback: Bank.ResultTransaction) {
        val api = apiClient
        if (api == null) {
            callback.onResult(-1, 0.0)
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val req = DepositRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName())
            val result = runBlocking { api.deposit(req) }
            val code = if (result.isSuccess) 0 else -1
            val balance = result.getOrElse { 0.0 }
            plugin.server.scheduler.runTask(plugin, Runnable { callback.onResult(code, balance) })
        })
    }

    fun asyncWithdraw(uuid: UUID, amount: Double, note: String, displayNote: String, callback: Bank.ResultTransaction) {
        val api = apiClient
        if (api == null) {
            callback.onResult(-1, 0.0)
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val req = WithdrawRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName())
            val result = runBlocking { api.withdraw(req) }
            val code = if (result.isSuccess) 0 else -1
            val balance = result.getOrElse { 0.0 }
            plugin.server.scheduler.runTask(plugin, Runnable { callback.onResult(code, balance) })
        })
    }

    fun asyncGetBalance(uuid: UUID, callback: Bank.ResultTransaction) {
        val api = apiClient
        if (api == null) {
            callback.onResult(-1, 0.0)
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val result = runBlocking { api.getBalance(uuid) }
            val code = if (result.isSuccess) 0 else -1
            val balance = result.getOrElse { 0.0 }
            plugin.server.scheduler.runTask(plugin, Runnable { callback.onResult(code, balance) })
        })
    }
}

// 旧APIのコールバック型互換
object Bank {
    interface ResultTransaction {
        fun onResult(resultCode: Int, newBalance: Double)
    }
}
