package red.man10.man10bank.model

import java.math.BigDecimal

enum class ResultCode(val message: String) {
    SUCCESS("成功"),
    INVALID_AMOUNT("金額が不正です"),
    INSUFFICIENT_FUNDS("残高が不足しています"),
    PROVIDER_UNAVAILABLE("外部プロバイダーが利用できません"),
    OVERFLOW("金額が上限を超えています"),
    FAILURE("処理に失敗しました"),
}

data class OperationResult(
    val code: ResultCode,
    val balance: BigDecimal? = null,
)

