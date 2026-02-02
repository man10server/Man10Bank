package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

@Serializable
data class LoanCreateRequest(
    val lendUuid: String,
    val borrowUuid: String,
    val borrowAmount: Double,
    val repayAmount: Double,
    val paybackDate: String, // ISO8601 date-time
    val collateralItem: String? = null,
)
