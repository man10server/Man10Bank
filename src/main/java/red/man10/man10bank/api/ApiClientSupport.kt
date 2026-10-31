package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.error.IndeterminateOutcomeException

/**
 * 各 ApiClient で重複していた HTTP 呼び出しの定型処理を集約する共通ヘルパ。
 *
 * - [getJson] / [postJson]: runCatching でラップし、レスポンスを型 [T] へデコードして
 *   Result<T> を返す。非2xx は HttpClientFactory の HttpResponseValidator により
 *   ApiHttpException へ正規化され、Result.failure として伝播する。
 * - [paging]: limit/offset のクエリパラメータ付与定型を 1 箇所に集約する。
 * - [retryIndeterminate]: 冪等キー付きの金銭操作を、結果不確定の失敗に限って同一内容で再送する。
 *
 * いずれも suspend 関数で、呼び出し側は Dispatchers.IO 等の非メインスレッドで実行すること。
 */

/**
 * limit/offset のページングパラメータを付与する。
 * - 負値は「未指定」とみなし送出しない（既存挙動を踏襲）。
 */
fun HttpRequestBuilder.paging(limit: Int, offset: Int) {
    if (limit >= 0) parameter("limit", limit)
    if (offset >= 0) parameter("offset", offset)
}

/**
 * GET でリソースを取得し [T] へデコードする。
 * @param block 追加のクエリパラメータ等を設定するブロック（任意）。
 */
suspend inline fun <reified T> HttpClient.getJson(
    path: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {},
): Result<T> = runCatching {
    get(path) { block() }.body()
}

/**
 * JSON ボディを送る POST を行い、レスポンスを [T] へデコードする。
 * @param body 送信するリクエストボディ（null の場合は本文なし）。
 * @param block 追加のクエリパラメータ等を設定するブロック（任意）。
 */
suspend inline fun <reified T> HttpClient.postJson(
    path: String,
    body: Any? = null,
    crossinline block: HttpRequestBuilder.() -> Unit = {},
): Result<T> = runCatching {
    post(path) {
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        block()
    }.body()
}

/** 結果不確定とみなす HTTP ステータス。中継経路(プロキシ等)の障害で、サーバー本体が処理済みか判別できない。 */
private val INDETERMINATE_STATUSES = setOf(408, 502, 503, 504)

/**
 * 失敗が「結果不確定」かどうかを判定する。
 * - ApiHttpException(サーバーが応答を返した)は原則として確定的な失敗(残高不足・検証エラー・500 等)。
 *   ただし 408/502/503/504 は中継障害でサーバー本体が処理済みの可能性があるため不確定として扱う。
 * - それ以外(リクエストタイムアウト・接続断・I/O 例外・2xx 応答の解釈失敗など)はすべて不確定。
 */
fun isIndeterminateFailure(cause: Throwable): Boolean = when (cause) {
    is ApiHttpException -> cause.status.value in INDETERMINATE_STATUSES
    else -> true
}

/**
 * 冪等な要求(requestId 付き)を、結果不確定の失敗に限って同一内容で再送する。
 * - サーバーは同じ requestId の再送に初回の結果を返すため、二重実行にはならない。
 * - 確定的な失敗(4xx/409/500 等)は再送せず即座に返す。
 * - 規定回数(初回 + [maxRetries] 回)を超えても不確定なら [IndeterminateOutcomeException] を失敗として返す。
 *   呼び出し側は「失敗」と断定せず、補償(返金等)を行わないこと。
 * - 再送間隔は [delayMs] × 試行回数の線形バックオフ。
 * - CancellationException は再送せず伝播する。
 */
suspend fun <T> retryIndeterminate(
    requestId: String,
    maxRetries: Int,
    delayMs: Long,
    block: suspend () -> Result<T>,
): Result<T> {
    val attempts = maxRetries.coerceAtLeast(0) + 1
    var lastCause: Throwable? = null
    repeat(attempts) { index ->
        val result = block()
        if (result.isSuccess) return result
        val cause = result.exceptionOrNull() ?: return result
        if (cause is CancellationException) throw cause
        if (!isIndeterminateFailure(cause)) return result
        lastCause = cause
        if (index < attempts - 1 && delayMs > 0) delay(delayMs * (index + 1))
    }
    return Result.failure(IndeterminateOutcomeException(requestId, attempts, lastCause!!))
}
