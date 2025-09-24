package red.man10.man10bank.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
 */
object HttpClientFactory {

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
                    val clientException = cause as? ClientRequestException?: return@handleResponseExceptionWithRequest
                    val response = clientException.response
                    val text = runCatching { response.bodyAsText() }.getOrNull()
                    val problemDetails = runCatching {
                        Json { ignoreUnknownKeys = true }
                            .decodeFromString(ProblemDetails.serializer(), text ?: "")
                    }.getOrNull()
                    throw ApiHttpException(
                        status = response.status,
                        problem = problemDetails,
                        message = problemDetails?.title?:"HTTP ${response.status.value} ${response.status.description}"
                    )
                }
            }

            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    }
                )
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
                    retryOnExceptionOrServerErrors(retries)
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
