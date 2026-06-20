package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * 電子マネー残高レスポンス。`{ "balance": number, "version": number }`。
 * - balance / version はいずれも整数。balance は整数円(Long)、version は単調増加カウンタ。
 */
@Serializable
data class VaultBalanceResponse(
    val balance: Long,
    val version: Long,
)

/** Vault 設定レスポンス。残高上限と Provider 移動緩和設定。 */
@Serializable
data class VaultConfigResponse(
    val maxBalance: Long,
    val joinReadyDelayMillis: Long,
    val quitDrainTimeoutMillis: Long,
)

/** 電子マネー送金(/pay)結果。送金元・送金先双方の更新後残高・version。 */
@Serializable
data class VaultTransferResponse(
    val fromBalance: Long,
    val fromVersion: Long,
    val toBalance: Long,
    val toVersion: Long,
)

/** user_vault と user_bank の移動結果。移動後の双方の残高(version は vault のみ)。 */
@Serializable
data class VaultMoveResponse(
    val vaultBalance: Long,
    val vaultVersion: Long,
    val bankBalance: Long,
)

/** 電子マネー取引ログ。 */
@Serializable
data class VaultLog(
    val id: Int = 0,
    val player: String = "",
    val uuid: String = "",
    val pluginName: String = "",
    val amount: Long = 0,
    val note: String = "",
    val displayNote: String = "",
    val server: String = "",
    val deposit: Boolean = true,
    val date: String = "",
    val operationId: String? = null,
    val source: String = "",
    val balanceAfter: Long = 0,
)
