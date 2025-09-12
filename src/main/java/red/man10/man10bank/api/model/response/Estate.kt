package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class Estate(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val date: String? = null,
    val vault: Double? = null,
    val bank: Double? = null,
    val cash: Double? = null,
    val estateAmount: Double? = null,
    val loan: Double? = null,
    val shop: Double? = null,
    val crypto: Double? = null,
    val total: Double? = null,
)

@Serializable
data class EstateHistory(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val date: String? = null,
    val vault: Double? = null,
    val bank: Double? = null,
    val cash: Double? = null,
    val estateAmount: Double? = null,
    val loan: Double? = null,
    val shop: Double? = null,
    val crypto: Double? = null,
    val total: Double? = null,
)
