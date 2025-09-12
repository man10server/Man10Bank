package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

@Serializable
data class LoanCreateRequest(
    val lendUuid: String,
    val borrowUuid: String,
    val amount: Double,
    val paybackDate: String, // ISO8601 date-time
    val collateralItem: String? = null,
)
