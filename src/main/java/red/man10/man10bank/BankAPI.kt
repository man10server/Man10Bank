package red.man10.man10bank

import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.error.ProblemDetails
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

    // ============ 同期API（結果付き・推奨） ============

    /**
     * 出金を行い、結果を返します。
     * 失敗時は [BankTransactionResult.errorMessage] 等から理由を参照できます。
     */
    fun tryWithdraw(uuid: UUID, amount: Double, note: String, displayNote: String): BankTransactionResult {
        val api = apiClient ?: return BankTransactionResult.unavailable()
        val req = WithdrawRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = serverName(),
        )
        return runBlocking { api.withdraw(req) }.toBankTransactionResult()
    }

    /**
     * 入金を行い、結果を返します。
     * 失敗時は [BankTransactionResult.errorMessage] 等から理由を参照できます。
     */
    fun tryDeposit(uuid: UUID, amount: Double, note: String, displayNote: String): BankTransactionResult {
        val api = apiClient ?: return BankTransactionResult.unavailable()
        val req = DepositRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = serverName(),
        )
        return runBlocking { api.deposit(req) }.toBankTransactionResult()
    }

    // ============ 同期API（互換・非推奨） ============

    @Deprecated(
        message = "失敗理由が取得できない。結果付きのtryWithdrawを使用してください。",
        replaceWith = ReplaceWith("tryWithdraw(uuid, amount, note, displayNote).success"),
        level = DeprecationLevel.WARNING,
    )
    fun withdraw(uuid: UUID, amount: Double, note: String, displayNote: String): Boolean =
        tryWithdraw(uuid, amount, note, displayNote).success

    @Deprecated(
        message = "displayNoteが設定できない。結果付きのtryWithdrawを使用してください。",
        replaceWith = ReplaceWith("tryWithdraw(uuid, amount, note, note).success"),
        level = DeprecationLevel.WARNING,
    )
    fun withdraw(uuid: UUID, amount: Double, note: String): Boolean =
        tryWithdraw(uuid, amount, note, note).success

    @Deprecated(
        message = "失敗理由が取得できない。結果付きのtryDepositを使用してください。",
        replaceWith = ReplaceWith("tryDeposit(uuid, amount, note, displayNote)"),
        level = DeprecationLevel.WARNING,
    )
    fun deposit(uuid: UUID, amount: Double, note: String, displayNote: String) {
        tryDeposit(uuid, amount, note, displayNote)
    }

    @Deprecated(
        message = "displayNoteが設定できない。結果付きのtryDepositを使用してください。",
        replaceWith = ReplaceWith("tryDeposit(uuid, amount, note, note)"),
        level = DeprecationLevel.WARNING,
    )
    fun deposit(uuid: UUID, amount: Double, note: String) {
        tryDeposit(uuid, amount, note, note)
    }

    fun getBalance(uuid: UUID): Double = runBlocking {
        val api = apiClient ?: return@runBlocking 0.0
        api.getBalance(uuid).getOrElse { 0.0 }
    }

    // ============ 非同期API（結果付き・推奨） ============

    /**
     * 入金を非同期で行い、結果を [callback] へ通知します。
     * コールバックはメインスレッドで呼び出されます。
     */
    fun asyncTryDeposit(
        uuid: UUID,
        amount: Double,
        note: String,
        displayNote: String,
        callback: Bank.ResultCallback,
    ) {
        val api = apiClient
        if (api == null) {
            callback.onResult(BankTransactionResult.unavailable())
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val req = DepositRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName())
            val result = runBlocking { api.deposit(req) }.toBankTransactionResult()
            plugin.server.scheduler.runTask(plugin, Runnable { callback.onResult(result) })
        })
    }

    /**
     * 出金を非同期で行い、結果を [callback] へ通知します。
     * コールバックはメインスレッドで呼び出されます。
     */
    fun asyncTryWithdraw(
        uuid: UUID,
        amount: Double,
        note: String,
        displayNote: String,
        callback: Bank.ResultCallback,
    ) {
        val api = apiClient
        if (api == null) {
            callback.onResult(BankTransactionResult.unavailable())
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val req = WithdrawRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName())
            val result = runBlocking { api.withdraw(req) }.toBankTransactionResult()
            plugin.server.scheduler.runTask(plugin, Runnable { callback.onResult(result) })
        })
    }

    // ============ 非同期API（互換・非推奨） ============

    @Deprecated(
        message = "失敗理由が取得できない。結果付きのasyncTryDepositを使用してください。",
        replaceWith = ReplaceWith("asyncTryDeposit(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
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

    @Deprecated(
        message = "失敗理由が取得できない。結果付きのasyncTryWithdrawを使用してください。",
        replaceWith = ReplaceWith("asyncTryWithdraw(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
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

// コールバック型
object Bank {
    /** 旧APIのコールバック型互換。resultCodeは成功時0、失敗時-1。 */
    interface ResultTransaction {
        fun onResult(resultCode: Int, newBalance: Double)
    }

    /** 結果付き非同期APIのコールバック型。 */
    fun interface ResultCallback {
        fun onResult(result: BankTransactionResult)
    }
}

/**
 * 入出金APIの実行結果。
 * 成功可否に加えて、失敗時はHTTPステータスやサーバーからのエラーメッセージで理由を参照できます。
 */
data class BankTransactionResult(
    /** 取引が成功したかどうか。 */
    val success: Boolean,
    /** 成功時の新しい残高。失敗時は 0.0。 */
    val balance: Double = 0.0,
    /** 失敗理由の表示用メッセージ。成功時は null。 */
    val errorMessage: String? = null,
    /** APIが返したHTTPステータスコード。取得できない場合は null。 */
    val httpStatus: Int? = null,
    /** APIが返した ProblemDetails。取得できない場合は null。 */
    val problem: ProblemDetails? = null,
) {
    companion object {
        /** 成功結果を生成します。 */
        fun success(balance: Double): BankTransactionResult =
            BankTransactionResult(success = true, balance = balance)

        /** 例外から失敗結果を生成します。 */
        fun failure(throwable: Throwable): BankTransactionResult {
            val api = throwable as? ApiHttpException
            val message = api?.problem?.detail
                ?: api?.problem?.title
                ?: throwable.message
                ?: "不明なエラー"
            return BankTransactionResult(
                success = false,
                errorMessage = message,
                httpStatus = api?.status?.value,
                problem = api?.problem,
            )
        }

        /** Man10Bank本体が読み込まれていない等でAPIを利用できない場合の結果。 */
        fun unavailable(): BankTransactionResult =
            BankTransactionResult(success = false, errorMessage = "Bank APIを利用できません")
    }
}

/** [Result] を [BankTransactionResult] へ変換します。 */
private fun Result<Double>.toBankTransactionResult(): BankTransactionResult =
    fold(
        onSuccess = { BankTransactionResult.success(it) },
        onFailure = { BankTransactionResult.failure(it) },
    )
