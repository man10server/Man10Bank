package red.man10.man10bank.api.model

import kotlinx.serialization.Serializable

/** Bank系のリクエストモデル */
@Serializable
data class DepositRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

@Serializable
data class WithdrawRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

