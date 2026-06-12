package red.man10.man10bank.util

import red.man10.man10bank.api.error.ApiHttpException

/**
 * ErrorCode名（ApiHttpException.code / ProblemDetails.code）に対応する日本語文言を返す。
 * - 未知のコードや null の場合は null を返す（呼び出し側でフォールバックする）。
 * - 例: "InsufficientFunds" -> "残高が不足しています"
 */
fun localizedMessageForCode(code: String?): String? = when (code) {
    null -> null
    "ValidationError" -> "入力内容に誤りがあります"
    "PlayerNotFound" -> "対象プレイヤーが見つかりません"
    "NotFound" -> "対象が見つかりません"
    "Conflict" -> "処理が競合したため失敗しました"
    "UnexpectedError" -> "予期しないエラーが発生しました"

    "InsufficientFunds" -> "残高が不足しています"
    "LimitOutOfRange" -> "limitの値が範囲外です"
    "OffsetOutOfRange" -> "offsetの値が範囲外です"
    "ChequeNotFound" -> "小切手が見つかりません"
    "ChequeAlreadyUsed" -> "小切手は既に使用されています"
    "EstateNotFound" -> "資産が見つかりません"
    "EstateUpdated" -> "資産が更新されました"
    "EstateNoChange" -> "資産に変更はありません"
    "LoanNotFound" -> "ローンが見つかりません"
    "BorrowLimitExceeded" -> "借入上限を超えています"
    "NoRepaymentNeeded" -> "返済の必要はありません"
    "PaymentAmountNotSet" -> "支払額が設定されていません"
    "PaymentAmountZero" -> "支払額が0です"
    "InterestStopped" -> "利息の計上は停止されています"
    "InterestZero" -> "利息が0です"
    "BeforePaybackDate" -> "返済期限前です"
    else -> null
}

/**
 * Result からエラー文言を取り出すユーティリティ。
 * - ApiHttpException を保持する場合はまず ProblemDetails の code（ErrorCode名）で日本語化を試みる。
 * - code で解決できない場合は例外の `message`（=ProblemDetails.title 等）を返し、
 *   それも無い/空のときは `defaultMessage` を返す。
 */
fun <T> resultErrorMessage(result: Result<T>, defaultMessage: String = "不明なエラー"): String {
    val cause = result.exceptionOrNull()
    // ApiHttpException の場合は extensions.code を優先して種別判定する
    if (cause is ApiHttpException) {
        localizedMessageForCode(cause.code)?.let { return it }
    }
    val raw = cause?.message?.trim()
    if (raw.isNullOrBlank()) return defaultMessage
    // 既知の短縮英語メッセージを日本語へ正規化。
    // ErrorCode名と一致する文言は localizedMessageForCode に一元化しているのでまずそちらへ委譲し、
    // ここではコード名ではない定型英語メッセージだけを追加で扱う（対応表の二重管理を避ける）。
    localizedMessageForCode(raw)?.let { return it }
    val normalized = when (raw) {
        "One or more validation Errors occurred." -> "1つ以上の検証エラーが発生しました"
        else -> null
    }
    return normalized ?: raw
}

/**
 * Result の拡張関数版。`result.errorMessage()` で簡潔に取得できます。
 */
fun <T> Result<T>.errorMessage(defaultMessage: String = "不明なエラー"): String =
    resultErrorMessage(this, defaultMessage)
