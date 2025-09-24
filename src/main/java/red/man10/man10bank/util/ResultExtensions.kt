package red.man10.man10bank.util

/**
 * Result からエラー文言を取り出すユーティリティ。
 * - 例外が保持されていればその `message` を返し、無い/空の場合は `defaultMessage` を返します。
 */
fun <T> resultErrorMessage(result: Result<T>, defaultMessage: String = "不明なエラー"): String {
    val msg = result.exceptionOrNull()?.message
    return if (msg.isNullOrBlank()) defaultMessage else msg
}

/**
 * Result の拡張関数版。`result.errorMessage()` で簡潔に取得できます。
 */
fun <T> Result<T>.errorMessage(defaultMessage: String = "不明なエラー"): String =
    resultErrorMessage(this, defaultMessage)

