package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.BorrowLimitResponse
import red.man10.man10bank.api.model.response.OverdueResponse
import red.man10.man10bank.api.model.response.PaymentInfoResponse
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.api.model.response.ServerLoanLog
import java.util.UUID

/** /api/ServerLoan 用のAPIクライアント */
class ServerLoanApiClient(private val client: HttpClient) {

    suspend fun get(uuid: UUID): Result<ServerLoan> = client.getJson("/api/ServerLoan/${uuid}")

    suspend fun borrow(uuid: UUID, body: ServerLoanBorrowBodyRequest): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/borrow", body)

    suspend fun repay(uuid: UUID, amount: Double): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/repay") {
            parameter("amount", amount)
        }

    suspend fun setPaymentAmount(uuid: UUID, paymentAmount: Double): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/payment-amount") {
            parameter("paymentAmount", paymentAmount)
        }

    suspend fun setBorrowAmount(uuid: UUID, amount: Double): Result<ServerLoan> =
        client.postJson("/api/ServerLoan/${uuid}/borrow-amount") {
            parameter("amount", amount)
        }

    suspend fun borrowLimit(uuid: UUID): Result<Double> =
        client.getJson<BorrowLimitResponse>("/api/ServerLoan/${uuid}/borrow-limit").map { it.limit }

    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>> =
        client.getJson("/api/ServerLoan/${uuid}/logs") { paging(limit, offset) }

    suspend fun paymentInfo(uuid: UUID): Result<PaymentInfoResponse> =
        client.getJson("/api/ServerLoan/${uuid}/payment-info")

    /** 残債があり、直近の定期返済が失敗のまま（延滞中）かどうか。 */
    suspend fun isOverdue(uuid: UUID): Result<Boolean> =
        client.getJson<OverdueResponse>("/api/ServerLoan/${uuid}/overdue").map { it.overdue }

    // ===== 契約の共有（リンク）。いずれも操作後の契約の状態を返す =====

    /** uuid が使っている契約と、共有の状態（参加者・承認待ちの申請）。 */
    suspend fun contract(uuid: UUID): Result<ServerLoanContract> =
        client.getJson("/api/ServerLoan/${uuid}/contract")

    /** uuid が target の契約への参加を申請する。 */
    suspend fun requestLink(uuid: UUID, target: UUID): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/request") { parameter("target", target.toString()) }

    /** 本垢 uuid が、member からの参加申請を承認する。 */
    suspend fun approveLink(uuid: UUID, member: UUID): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/approve") { parameter("member", member.toString()) }

    /** 本垢 uuid が、member からの参加申請を拒否する。 */
    suspend fun rejectLink(uuid: UUID, member: UUID): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/reject") { parameter("member", member.toString()) }

    /** uuid が、自分の出した参加申請を取り消す。 */
    suspend fun cancelLinkRequest(uuid: UUID): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/cancel")

    /** uuid が、参加している契約から脱退する（契約に残債がある間は不可）。 */
    suspend fun leaveLink(uuid: UUID): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/leave")

    /** 運営用: uuid を owner の契約に申請・承認なしで参加させる。resetLimit で参加先の借入枠を初期枠に戻す。 */
    suspend fun forceLink(uuid: UUID, owner: UUID, operator: String, resetLimit: Boolean): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/force") {
            parameter("owner", owner.toString())
            parameter("operator", operator)
            parameter("resetLimit", resetLimit)
        }

    /** 運営用: uuid の参加（または承認待ちの申請）を取り消す。契約に残債があっても行える。 */
    suspend fun forceUnlink(uuid: UUID, operator: String): Result<ServerLoanContract> =
        client.postJson("/api/ServerLoan/${uuid}/link/remove") { parameter("operator", operator) }
}
