package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class PaymentInfoResponse(
    val nextRepayDate: String,
    val dailyInterestPerDay: Double,
)

