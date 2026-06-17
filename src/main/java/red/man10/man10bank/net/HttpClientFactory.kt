package red.man10.man10bank.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.error.ProblemDetails
import red.man10.man10bank.config.ConfigManager.ApiConfig

/**
 * Ktor HttpClient を作成するファクトリ。
 * - インスタンスは長寿命で再利用することを前提としています。
 *
 * 設定マッピング（ConfigManager.ApiConfig -> HttpClient 設定）
 * - baseUrl: すべてのリクエストのベースURLとして `DefaultRequest` で設定します。
 * - apiKey: 指定時に `Authorization: Bearer <key>` を自動付与します。
 * - timeouts:
 *   - requestMs: リクエスト全体のタイムアウト（`HttpTimeout.requestTimeoutMillis`）。
 *   - connectMs: 接続確立のタイムアウト（`connectTimeoutMillis`）。
 *   - socketMs : 読み書きのタイムアウト（`socketTimeoutMillis`）。
 * - retries: 0 より大きい場合は `HttpRequestRetry` を有効化し指数バックオフで再試行します。
 *   ただし冪等メソッド(GET/HEAD)のみが再試行対象で、POST等の非冪等メソッドは
 *   二重実行（二重入金/二重出金）を防ぐため一切リトライしません。
 */
object HttpClientFactory {
    // 冪等メソッド集合。これらのみリトライ対象とする。
    private val IDEMPOTENT_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

    // JSONフォーマットは使い回してコストを下げる
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * ApiConfig から HttpClient を生成します。
     *
     * 引数:
     * - config.baseUrl: ベースURL（例: https://api.example.com）。未設定は ConfigManager 側で拒否します。
     * - config.apiKey : Bearer 認証トークン。不要なら null/空文字。
     * - config.timeouts: リクエスト/接続/ソケットの各タイムアウト（ミリ秒）。
     * - config.retries : 失敗時の自動リトライ回数。0 で無効。
     * - engine        : テスト時などに `MockEngine` を注入可能。未指定時は CIO を使用します。
     *
     * 注意:
     * - `expectSuccess = true` のため、4xx/5xx は例外になります（サービス層で捕捉してください）。
     * - クライアントは長寿命で使い回し、プラグイン停止時に `close()` してください。
     */
    fun create(config: ApiConfig, engine: HttpClientEngine? = null): HttpClient {
        return HttpClient(engine ?: CIO.create()) {
            // 2xx 以外を例外として扱う
            expectSuccess = true

            HttpResponseValidator {
                handleResponseExceptionWithRequest { cause, _ ->
                    // 4xx(ClientRequestException) と 5xx(ServerResponseException) の両方を正規化する。
                    // どちらも ResponseException を継承し response を持つ。
                    val responseException = cause as? ResponseException ?: return@handleResponseExceptionWithRequest
                    val response = responseException.response
                    val text = runCatching { response.bodyAsText() }.getOrNull()
                    // ProblemDetails のパースに失敗しても生本文を失わないよう先頭500文字を保持する。
                    val rawBody = text?.takeIf { it.isNotBlank() }?.take(500)
                    val problemDetails = runCatching {
                        jsonFormat.decodeFromString(ProblemDetails.serializer(), text ?: "")
                    }.getOrNull()
                    val message = problemDetails?.title
                        ?: rawBody
                        ?: "HTTP ${response.status.value} ${response.status.description}"
                    throw ApiHttpException(
                        status = response.status,
                        problem = problemDetails,
                        rawBody = rawBody,
                        message = message,
                    )
                }
            }

            install(ContentNegotiation) { json(jsonFormat) }

            // 電子マネー(Vault Provider)の push/presence 用に WebSocket を有効化する。
            // REST 利用には影響しない。
            install(WebSockets) {
                // クライアント側からも定期 ping を送り、サーバーが無言で落ちた場合の切断検知を
                // この間隔程度に短縮する（受動 pong のみだと TCP エラーまで数十秒かかる。VaultProvider 4.6）。
                if (config.wsPingIntervalMs > 0) {
                    pingInterval = config.wsPingIntervalMs
                }
            }

            install(HttpTimeout) {
                // タイムアウトはすべてミリ秒単位
                requestTimeoutMillis = config.timeouts.requestMs
                connectTimeoutMillis = config.timeouts.connectMs
                socketTimeoutMillis = config.timeouts.socketMs
            }

            // リトライ回数は 0..5 にクランプ
            val retries = config.retries.coerceIn(0, 5)
            if (retries > 0) {
                install(HttpRequestRetry) {
                    maxRetries = retries
                    // 冪等メソッド(GET/HEAD)のみリトライする。
                    // POST等の非冪等メソッドは二重入金/二重出金を防ぐため一切リトライしない。
                    retryIf { request, response ->
                        request.method in IDEMPOTENT_METHODS && response.status.value >= 500
                    }
                    retryOnExceptionIf { request, _ ->
                        request.method in IDEMPOTENT_METHODS
                    }
                    exponentialDelay()
                }
            }

            install(DefaultRequest) {
                // すべてのリクエストにベースURL/共通ヘッダを適用
                url(config.baseUrl)
                headers {
                    append(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    if (!config.apiKey.isNullOrBlank()) {
                        append(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    }
                }
            }
        }
    }

}
