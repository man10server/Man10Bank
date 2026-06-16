package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

/**
 * 電子マネー(Vault Provider)系のリクエストモデル。
 * 金額は Bank 系と同様に Double（境界で整数化済み）で送る。
 */
@Serializable
data class VaultDepositRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

@Serializable
data class VaultWithdrawRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

/**
 * 電子マネー送金(/pay)。fromUuid から toUuid へ amount を単一トランザクションで移動する。
 * 送受信者がともに同一サーバーに在席している場合のみ実行する（プラグイン層で判定。VaultProvider 4.5）。
 */
@Serializable
data class VaultTransferRequest(
    val fromUuid: String,
    val toUuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

/**
 * 管理用: 電子マネー残高を絶対値で設定する。
 * オフライン(在席不明)プレイヤーを変更できる唯一の経路（VaultProvider 4.5）。
 */
@Serializable
data class VaultSetRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
)

/** 電子マネー ⇄ 銀行残高の移動方向（サーバーの enum 名と一致させる）。 */
enum class VaultMoveDirection { VaultToBank, BankToVault }

/**
 * 電子マネー ⇄ 銀行残高を 1 トランザクションで移動する（ATM/`/deposit`/`/withdraw` 用。VaultProvider 7.2）。
 * direction はサーバーの enum 名（"VaultToBank" / "BankToVault"）を文字列で送る。
 */
@Serializable
data class VaultMoveRequest(
    val uuid: String,
    val amount: Double,
    val direction: String,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
) {
    constructor(
        uuid: String,
        amount: Double,
        direction: VaultMoveDirection,
        pluginName: String,
        note: String,
        displayNote: String,
        server: String,
    ) : this(uuid, amount, direction.name, pluginName, note, displayNote, server)
}
