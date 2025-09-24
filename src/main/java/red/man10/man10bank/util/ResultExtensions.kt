package red.man10.man10bank.util

/**
 * Result からエラー文言を取り出すユーティリティ。
 * - 例外が保持されていればその `message` を返し、無い/空の場合は `defaultMessage` を返します。
 */
fun <T> resultErrorMessage(result: Result<T>, defaultMessage: String = "不明なエラー"): String {
    val raw = result.exceptionOrNull()?.message?.trim()
    if (raw.isNullOrBlank()) return defaultMessage
    // 既知の短縮英語メッセージを日本語へ正規化
    val normalized = when (raw) {
        "One or more validation Errors occurred." -> "1つ以上の検証エラーが発生しました"
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
    return normalized ?: raw
}

/**
 * Result の拡張関数版。`result.errorMessage()` で簡潔に取得できます。
 */
fun <T> Result<T>.errorMessage(defaultMessage: String = "不明なエラー"): String =
    resultErrorMessage(this, defaultMessage)
