package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class AtmLog(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val amount: Double? = null,
    val deposit: Boolean? = null,
    val date: String? = null, // ISO8601
)
