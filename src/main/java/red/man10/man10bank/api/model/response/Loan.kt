package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class Loan(
    val id: Int? = null,
    val lendPlayer: String,
    val lendUuid: String,
    val borrowPlayer: String,
    val borrowUuid: String,
    val borrowDate: String? = null,
    val paybackDate: String? = null,
    val amount: Double? = null,
    val collateralItem: String? = null,
)
