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

/**
 * 各 ApiClient で重複していた HTTP 呼び出しの定型処理を集約する共通ヘルパ。
 *
 * - [getJson] / [postJson]: runCatching でラップし、レスポンスを型 [T] へデコードして
 *   Result<T> を返す。非2xx は HttpClientFactory の HttpResponseValidator により
 *   ApiHttpException へ正規化され、Result.failure として伝播する。
 * - [paging]: limit/offset のクエリパラメータ付与定型を 1 箇所に集約する。
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
