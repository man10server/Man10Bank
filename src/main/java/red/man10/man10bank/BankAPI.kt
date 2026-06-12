package red.man10.man10bank

import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.error.ProblemDetails
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import java.util.*

/**
 * 旧バージョン互換のBankAPI。
 * - 既存プラグインの呼び出しシグネチャを保持しつつ、内部ではWebAPIクライアントへ委譲します。
 * - BankService側の修正は行いません。ロケータも使用しません。
 */
class BankAPI(private val plugin: JavaPlugin) {

    private val man10Bank: Man10Bank?
        get() = Bukkit.getPluginManager().getPlugin("Man10Bank") as? Man10Bank

    /**
     * 本体 Man10Bank が保持する共有 BankApiClient を取得する。
     * - 独自に HttpClient を生成せず本体の単一クライアントを再利用するため、
     *   コネクション/スレッドプールのリークが発生しない。
     * - 本体未ロード時は null。
     */
    private val apiClient: BankApiClient?
        get() = man10Bank?.sharedBankApiClient

    private fun serverName(): String = man10Bank?.serverName ?: plugin.server.name

    /**
     * 同期メソッドがメインスレッドから呼ばれた場合に警告ログを出す。
     * - HTTP往復の間サーバーTPSが停止するため、非同期版(asyncTry*)の利用を促す。
     */
    private fun warnIfPrimaryThread(method: String) {
        if (Bukkit.isPrimaryThread()) {
            plugin.logger.warning(
                "BankAPI.$method がメインスレッドから呼び出されました。" +
                    "HTTP通信の間サーバーがブロックされます。非同期版(asyncTry${method.replaceFirstChar { it.uppercase() }} 等)の利用を推奨します。"
            )
        }
    }

    // ============ 同期API（結果付き・推奨） ============

    /**
     * 出金を行い、結果を返します。
     * 失敗時は [BankTransactionResult.errorMessage] 等から理由を参照できます。
     *
     * 注意: 内部で runBlocking によるHTTP同期呼び出しを行うため、メインスレッドからは呼ばないでください
     * （メインスレッドから呼ぶとサーバーがブロックされ、検知時は警告ログを出します）。
     * 非同期版 [asyncTryWithdraw] の利用を推奨します。
     */
    @Deprecated(
        message = "メインスレッドをブロックしうる同期API。非同期版の asyncTryWithdraw を使用してください。",
        replaceWith = ReplaceWith("asyncTryWithdraw(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
    fun tryWithdraw(uuid: UUID, amount: Double, note: String, displayNote: String): BankTransactionResult {
        warnIfPrimaryThread("tryWithdraw")
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
     *
     * 注意: 内部で runBlocking によるHTTP同期呼び出しを行うため、メインスレッドからは呼ばないでください
     * （メインスレッドから呼ぶとサーバーがブロックされ、検知時は警告ログを出します）。
     * 非同期版 [asyncTryDeposit] の利用を推奨します。
     */
    @Deprecated(
        message = "メインスレッドをブロックしうる同期API。非同期版の asyncTryDeposit を使用してください。",
        replaceWith = ReplaceWith("asyncTryDeposit(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
    fun tryDeposit(uuid: UUID, amount: Double, note: String, displayNote: String): BankTransactionResult {
        warnIfPrimaryThread("tryDeposit")
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
        message = "失敗理由が取得できない、かつメインスレッドをブロックしうる。非同期版の asyncTryWithdraw を使用してください。",
        replaceWith = ReplaceWith("asyncTryWithdraw(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    fun withdraw(uuid: UUID, amount: Double, note: String, displayNote: String): Boolean =
        tryWithdraw(uuid, amount, note, displayNote).success

    @Deprecated(
        message = "displayNoteが設定できない、かつメインスレッドをブロックしうる。非同期版の asyncTryWithdraw を使用してください。",
        replaceWith = ReplaceWith("asyncTryWithdraw(uuid, amount, note, note, callback)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    fun withdraw(uuid: UUID, amount: Double, note: String): Boolean =
        tryWithdraw(uuid, amount, note, note).success

    @Deprecated(
        message = "失敗理由が取得できない、かつメインスレッドをブロックしうる。非同期版の asyncTryDeposit を使用してください。",
        replaceWith = ReplaceWith("asyncTryDeposit(uuid, amount, note, displayNote, callback)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    fun deposit(uuid: UUID, amount: Double, note: String, displayNote: String) {
        tryDeposit(uuid, amount, note, displayNote)
    }

    @Deprecated(
        message = "displayNoteが設定できない、かつメインスレッドをブロックしうる。非同期版の asyncTryDeposit を使用してください。",
        replaceWith = ReplaceWith("asyncTryDeposit(uuid, amount, note, note, callback)"),
        level = DeprecationLevel.WARNING,
    )
    @Suppress("DEPRECATION")
    fun deposit(uuid: UUID, amount: Double, note: String) {
        tryDeposit(uuid, amount, note, note)
    }

    /**
     * 残高を取得します（互換維持）。
     *
     * 重要: この関数は **取得失敗時にも 0.0 を返す** ため、本物の残高0と通信障害を区別できません。
     * 残高チェック等の判定に使うと、通信障害時に残高0と誤認する危険があります。
     * 失敗を区別したい場合は [getBalanceOrNull] を使用してください。
     *
     * また内部で runBlocking によるHTTP同期呼び出しを行うため、メインスレッドからは呼ばないでください
     * （メインスレッドから呼ぶとサーバーがブロックされ、検知時は警告ログを出します）。
     */
    @Deprecated(
        message = "失敗時0.0と残高0を区別できず、メインスレッドをブロックしうる。getBalanceOrNull を使用してください。",
        replaceWith = ReplaceWith("getBalanceOrNull(uuid)"),
        level = DeprecationLevel.WARNING,
    )
    fun getBalance(uuid: UUID): Double = getBalanceOrNull(uuid) ?: 0.0

    /**
     * 残高を取得します。取得に失敗した場合は null を返します（本物の残高0と区別可能）。
     *
     * 注意: 内部で runBlocking によるHTTP同期呼び出しを行うため、メインスレッドからは呼ばないでください
     * （メインスレッドから呼ぶとサーバーがブロックされ、検知時は警告ログを出します）。
     */
    fun getBalanceOrNull(uuid: UUID): Double? {
        warnIfPrimaryThread("getBalance")
        val api = apiClient ?: return null
        return runBlocking { api.getBalance(uuid) }.getOrNull()
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
