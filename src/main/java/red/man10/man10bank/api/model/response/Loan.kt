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
    /** 担保が回収(債権者)または返還(債務者)済みか。 */
    val collateralReleased: Boolean = false,
) {
    /** 担保が設定されていて、まだ回収・返還されていない。 */
    val hasPendingCollateral: Boolean
        get() = !collateralItem.isNullOrBlank() && !collateralReleased
}
