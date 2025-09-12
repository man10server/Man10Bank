package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

@Serializable
data class ServerLoanBorrowBodyRequest(
    val amount: Double,
)
