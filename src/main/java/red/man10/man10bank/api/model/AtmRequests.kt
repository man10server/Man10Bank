package red.man10.man10bank.api.model

import kotlinx.serialization.Serializable

@Serializable
data class AtmLogRequest(
    val uuid: String,
    val amount: Double,
    val deposit: Boolean,
)

