package red.man10.man10bank.api.model

import kotlinx.serialization.Serializable

@Serializable
data class EstateUpdateRequest(
    val cash: Double? = null,
    val vault: Double? = null,
    val estateAmount: Double? = null,
    val shop: Double? = null,
)

