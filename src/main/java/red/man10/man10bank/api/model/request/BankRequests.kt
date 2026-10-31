package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

/**
 * Bank系のリクエストモデル。
 * - requestId は要求ごとに生成する冪等キー(UUID)。タイムアウト等で結果不明になった要求を
 *   同じ requestId で再送すると、サーバーは二重実行せず初回の結果を返す。
 */
@Serializable
data class DepositRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val requestId: String,
)

@Serializable
data class WithdrawRequest(
    val uuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val requestId: String,
)

/**
 * 送金リクエスト（DESIGN 1.5）。
 * - `POST /api/Bank/transfer` の本文。
 * - fromUuid/toUuid は36文字UUID、amount > 0、fromUuid != toUuid。
 * - 出金と入金、MoneyLog2件をサーバー側の単一トランザクションで処理する。
 */
@Serializable
data class TransferRequest(
    val fromUuid: String,
    val toUuid: String,
    val amount: Double,
    val pluginName: String,
    val note: String,
    val displayNote: String,
    val server: String,
    val requestId: String,
)
