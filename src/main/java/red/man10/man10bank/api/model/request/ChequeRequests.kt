package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

@Serializable
data class ChequeCreateRequest(
    val uuid: String,
    val amount: Double,
    val note: String? = null,
)

@Serializable
data class ChequeUseRequest(
    val uuid: String,
)
