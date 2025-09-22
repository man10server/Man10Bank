package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class LoanRepayResponse(
    val loanId: Int,
    val outcome: Int,
    val collectedAmount: Double,
    val remainingAmount: Double,
    val collateralItem: String? = null,
)

