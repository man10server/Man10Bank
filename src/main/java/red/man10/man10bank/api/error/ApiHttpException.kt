package red.man10.man10bank.api.error

import io.ktor.http.*

/**
 * API層でHTTPエラー(非2xx)を受け取った際にスローする例外。
 * プレイヤー/コンソールへは message(=ProblemDetails.title等) を表示する想定。
 */
class ApiHttpException(
    val status: HttpStatusCode,
    val problem: ProblemDetails? = null,
    message: String
) : Exception(message)

