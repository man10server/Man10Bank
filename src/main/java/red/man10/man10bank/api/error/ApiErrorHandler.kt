package red.man10.man10bank.api.error

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json

/**
 * API呼び出しの例外をProblemDetailsベースの短いメッセージへ正規化するヘルパ。
 */
object ApiErrorHandler {

    suspend fun <T> run(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ResponseException) {
        Result.failure(toApiHttpException(e))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun toApiHttpException(e: ResponseException): ApiHttpException {
        val status = e.response.status
        val text = runCatching { e.response.bodyAsText() }.getOrNull()
        val problem = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(ProblemDetails.serializer(), text ?: "")
        }.getOrNull()
        val message = problem?.title?.takeIf { it.isNotBlank() }
            ?: problem?.detail?.takeIf { it.isNotBlank() }
            ?: defaultMessageForStatus(status)
        return ApiHttpException(status, problem, message)
    }

    fun defaultMessageForStatus(status: HttpStatusCode): String = when (status) {
        HttpStatusCode.BadRequest -> "不正なリクエストです。入力内容をご確認ください。"
        HttpStatusCode.Conflict -> "要求が競合しました。状態を確認して再実行してください。"
        HttpStatusCode.Unauthorized -> "認証に失敗しました。権限やAPIキーを確認してください。"
        HttpStatusCode.Forbidden -> "許可されていない操作です。"
        HttpStatusCode.NotFound -> "対象が見つかりませんでした。"
        else -> "処理に失敗しました。（HTTP ${'$'}{status.value}）"
    }
}

