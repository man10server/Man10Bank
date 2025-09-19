package red.man10.man10bank.service

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * サーバーローン機能のサービス（型のみ）。
 * - ServerLoanApiClient を利用（実装は後続で追加）
 * - Listener は継承しない
 */
class ServerLoanService(
    private val plugin: Man10Bank,
    private val api: ServerLoanApiClient,
) {

    /**
     * プレイヤーから取得
     */
    suspend fun get(player: Player): Result<ServerLoan> = api.get(player.uniqueId)

    /**
     * 借入処理（プレイヤー指定）。
     * - 引数: プレイヤーと金額
     * - 戻り値: なし（メッセージ送信は本メソッド内で行う）
     */
    suspend fun borrow(player: Player, amount: Double) {
        if (amount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.borrow(player.uniqueId, ServerLoanBorrowBodyRequest(amount))
        if (result.isSuccess) {
            val loan = result.getOrNull()
            val paymentInfo = loan?.paymentAmount?.let { BalanceFormats.colored(it) } ?: "未設定"
            Messages.send(plugin, player,
                "§a借入に成功しました。金額: ${BalanceFormats.colored(amount)} §a支払額: $paymentInfo"
            )
        } else {
            val msg = result.exceptionOrNull()?.message ?: "借入に失敗しました。"
            Messages.error(plugin, player, msg)
        }
    }

    /**
     * 返済処理（プレイヤー指定）。
     * - 引数: プレイヤーと金額
     * - 戻り値: なし（メッセージ送信は本メソッド内で行う）
     */
    suspend fun repay(player: Player, amount: Double) {
        if (amount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.repay(player.uniqueId, amount)
        if (result.isSuccess) {
            val loan = result.getOrNull()
            val remainingInfo = loan?.borrowAmount?.let { BalanceFormats.colored(it) } ?: "不明"
            Messages.send(plugin, player,
                "§a返済に成功しました。金額: ${BalanceFormats.colored(amount)} §a残額: $remainingInfo"
            )
        } else {
            val msg = result.exceptionOrNull()?.message ?: "返済に失敗しました。"
            Messages.error(plugin, player, msg)
        }
    }

    /**
     * 支払額の設定（プレイヤー指定）。
     * - paymentAmount が null の場合は未設定に戻す
     * - 正の数のみ許容
     */
    suspend fun setPaymentAmount(player: Player, paymentAmount: Double?) {
        if (paymentAmount != null && paymentAmount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.setPaymentAmount(player.uniqueId, paymentAmount)
        if (result.isSuccess) {
            val updated = result.getOrNull()
            val info = updated?.paymentAmount?.let { BalanceFormats.colored(it) } ?: "未設定"
            Messages.send(plugin, player, "支払額を更新しました。支払額: $info")
        } else {
            val msg = result.exceptionOrNull()?.message ?: "支払額の更新に失敗しました。"
            Messages.error(plugin, player, msg)
        }
    }
}
