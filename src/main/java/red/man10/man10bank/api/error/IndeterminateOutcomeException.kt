package red.man10.man10bank.api.error

/**
 * 金銭操作(入金/出金/送金)の結果をサーバーから確認できなかったことを表す例外。
 * - タイムアウトや接続断などで、サーバーが処理を「行ったか行っていないか」判別できない状態。
 * - 同一 requestId での再送を規定回数行っても確定しなかった場合に API クライアントが失敗として返す。
 * - 呼び出し側はこれを「失敗」と断定して補償(返金等)を行ってはならない。
 *   サーバー側で処理済みの可能性があり、補償するとお金の増殖になる。
 */
class IndeterminateOutcomeException(
    /** 再送に使った冪等キー。サーバーの idempotency_key テーブルで処理有無を照会できる。 */
    val requestId: String,
    /** 試行回数(初回 + 再送)。 */
    val attempts: Int,
    cause: Throwable,
) : Exception(
    "サーバーからの応答を確認できませんでした(requestId=$requestId, attempts=$attempts): ${cause.message}",
    cause,
)
