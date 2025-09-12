package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class MoneyLog(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val pluginName: String,
    val amount: Double? = null,
    val note: String,
    val displayNote: String,
    val server: String,
    val deposit: Boolean? = null,
    val date: String? = null,
)
