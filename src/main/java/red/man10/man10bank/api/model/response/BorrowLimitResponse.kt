package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * 借入上限レスポンス（DESIGN 1.4）。
 * - `GET /api/ServerLoan/{uuid}/borrow-limit` が返す `{ "limit": number }` 形式に対応する。
 */
@Serializable
data class BorrowLimitResponse(
    val limit: Double,
)
