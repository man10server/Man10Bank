package red.man10.man10bank.api.error

import io.ktor.http.*

/**
 * API層でHTTPエラー(非2xx)を受け取った際にスローする例外。
 * プレイヤー/コンソールへは message(=ProblemDetails.title等) を表示する想定。
 *
 * - [problem] は ProblemDetails のパースに成功した場合のみ非null。
 * - [rawBody] はサーバーが返した生のレスポンス本文（先頭500文字まで）。
 *   ProblemDetails のパースに失敗した場合でもエラー本文を失わないために保持する。
 * - [code] は ProblemDetails.code（extensions.code / ErrorCode名）。エラー種別判定に使用する。
 */
class ApiHttpException(
    val status: HttpStatusCode,
    val problem: ProblemDetails? = null,
    val rawBody: String? = null,
    message: String
) : Exception(message) {
    /** サーバーが返した ErrorCode 名（例: "InsufficientFunds"）。取得できない場合は null。 */
    val code: String?
        get() = problem?.code
}

