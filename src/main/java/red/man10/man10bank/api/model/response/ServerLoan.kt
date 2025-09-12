package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class ServerLoan(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val borrowDate: String? = null,
    val lastPayDate: String? = null,
    val borrowAmount: Double? = null,
    val paymentAmount: Double? = null,
    val failedPayment: Int? = null,
    val stopInterest: Boolean? = null,
)

@Serializable
data class ServerLoanLog(
    val id: Int? = null,
    val player: String,
    val uuid: String,
    val action: String,
    val amount: Double? = null,
    val date: String? = null,
)
