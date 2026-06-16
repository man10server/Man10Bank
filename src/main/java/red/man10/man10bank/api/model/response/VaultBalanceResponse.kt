package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * 電子マネー残高レスポンス（VaultProvider 4.3）。
 * - `GET /api/Vault/{uuid}/balance` および deposit/withdraw/transfer/set/ensure が返す
 *   `{ "balance": number, "version": number }`。
 * - version は user_vault.Version（単調増加）。キャッシュ適用順序の判定に使う。
 */
@Serializable
data class VaultBalanceResponse(
    val balance: Double,
    val version: Long,
)

/**
 * 電子マネー ⇄ 銀行移動の結果（VaultProvider 7.2）。
 * - `POST /api/Vault/move` が返す `{ "vaultBalance", "bankBalance", "vaultVersion" }`。
 */
@Serializable
data class VaultMoveResponse(
    val vaultBalance: Double,
    val bankBalance: Double,
    val vaultVersion: Long,
)
