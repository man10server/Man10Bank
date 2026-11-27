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

/**
 * あるプレイヤーが使っているサーバーローンの契約と、共有（リンク）の状態。
 * - ownerUuid / ownerPlayer: 契約の本垢。誰の契約にも参加していなければ本人。
 * - members: この契約に参加しているアカウント（参加が古い順）。
 * - pendingRequests: この契約への参加申請のうち、本垢の承認を待っているもの。
 * - outgoingRequest: 本人が他のプレイヤーの契約へ出している、承認待ちの申請。
 */
@Serializable
data class ServerLoanContract(
    val ownerUuid: String,
    val ownerPlayer: String = "",
    val members: List<ServerLoanMember> = emptyList(),
    val pendingRequests: List<ServerLoanMember> = emptyList(),
    val outgoingRequest: ServerLoanLinkRequest? = null,
)

@Serializable
data class ServerLoanMember(
    val uuid: String,
    val player: String = "",
    val linkedBy: String = "",
    val requestedAt: String? = null,
    val approvedAt: String? = null,
)

@Serializable
data class ServerLoanLinkRequest(
    val ownerUuid: String,
    val ownerPlayer: String = "",
    val requestedAt: String? = null,
)

@Serializable
data class PaymentInfoResponse(
    val nextRepayDate: String,
    val dailyInterestPerDay: Double,
    val nextPaymentAmount: Double? = null,
)