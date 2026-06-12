package red.man10.man10bank.api.error

import kotlinx.serialization.Serializable

/**
 * ASP.NET Core の ProblemDetails モデル（RFC7807）。
 * ステータスコード非200の際にAPIが返却する標準エラー。
 *
 * - サーバーは `extensions.code` に ErrorCode 名（例: "InsufficientFunds"）を必ず含めます。
 *   ASP.NET Core では拡張メンバーはトップレベルへフラットに展開されるため、
 *   `code` をトップレベルのフィールドとして受け取ります（エラー種別判定に使用）。
 */
@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    /** extensions.code（ErrorCode名）。エラー種別判定に使用する。 */
    val code: String? = null,
)

