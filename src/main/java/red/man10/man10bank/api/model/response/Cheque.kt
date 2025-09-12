package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class Cheque(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val amount: Double? = null,
    val note: String? = null,
    val date: String? = null,
    val useDate: String? = null,
    val usePlayer: String? = null,
    val used: Boolean? = null,
)
