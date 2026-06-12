package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * 残高レスポンス（DESIGN 1.4）。
 * - `GET /api/Bank/{uuid}/balance` および `POST /api/Bank/deposit` / `withdraw` / `transfer`
 *   が返す `{ "balance": number }` 形式に対応する。
 * - transfer の場合 balance は送金元の新残高。
 */
@Serializable
data class BalanceResponse(
    val balance: Double,
)
