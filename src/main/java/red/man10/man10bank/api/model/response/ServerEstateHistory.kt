package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class ServerEstateHistory(
    val id: Int? = null,
    val vault: Double? = null,
    val bank: Double? = null,
    val cash: Double? = null,
    val estateAmount: Double? = null,
    val loan: Double? = null,
    val crypto: Double? = null,
    val shop: Double? = null,
    val total: Double? = null,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val hour: Int? = null,
    val date: String? = null, // ISO8601
)

