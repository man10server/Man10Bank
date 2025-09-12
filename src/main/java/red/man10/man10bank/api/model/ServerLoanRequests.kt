package red.man10.man10bank.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerLoanBorrowBodyRequest(
    val amount: Double,
)

