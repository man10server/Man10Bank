package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

/**
 * Vault(電子マネー)系のリクエストモデル。
 * - 金額は整数円(Long)。Man10BankService 側は decimal(20,0) として受ける。
 * - operationId は冪等キー。Provider 送信待ちキュー/内製APIの再送で同一値を使う。
 * - source は操作元種別("PROVIDER" / "MAN10_API")。未指定時はサービス側で MAN10_API を既定とする。
 */
@Serializable
data class VaultDepositRequest(
    val uuid: String,
    val amount: Long,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val operationId: String? = null,
    val source: String? = null,
)

@Serializable
data class VaultWithdrawRequest(
    val uuid: String,
    val amount: Long,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val operationId: String? = null,
    val source: String? = null,
)

/** 電子マネー送金(/pay)。送金元・送金先ともに電子マネー(user_vault)。 */
@Serializable
data class VaultTransferRequest(
    val fromUuid: String,
    val toUuid: String,
    val amount: Long,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val operationId: String? = null,
)

/**
 * user_vault と user_bank の相互移動(/deposit /withdraw)。
 * - direction: "VaultToBank"(/deposit: 電子マネー減) / "BankToVault"(/withdraw: 電子マネー増)。
 */
@Serializable
data class VaultMoveRequest(
    val uuid: String,
    val amount: Long,
    val direction: String,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val operationId: String? = null,
)

/** 管理者用の絶対値設定(setBalance / editvault)。 */
@Serializable
data class VaultSetRequest(
    val uuid: String,
    val amount: Long,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val operationId: String? = null,
)
