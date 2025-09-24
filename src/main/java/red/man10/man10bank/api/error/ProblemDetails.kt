package red.man10.man10bank.api.error

import kotlinx.serialization.Serializable

/**
 * ASP.NET Core の ProblemDetails モデル。
 * ステータスコード非200の際にAPIが返却する標準エラー。
 */
@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
)

