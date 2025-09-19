package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanLog
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import java.util.UUID

/**
 * サーバーローン機能のサービス（型のみ）。
 * - ServerLoanApiClient を利用（実装は後続で追加）
 * - Listener は継承しない
 */
class ServerLoanService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: ServerLoanApiClient,
) {

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

    suspend fun get(uuid: UUID): Result<ServerLoan> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#get 未実装")
    }

    suspend fun borrow(uuid: UUID, amount: Double): Result<ServerLoan> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#borrow 未実装")
    }

    suspend fun repay(uuid: UUID, amount: Double?): Result<ServerLoan> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#repay 未実装")
    }

    suspend fun setPaymentAmount(uuid: UUID, paymentAmount: Double?): Result<ServerLoan> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#setPaymentAmount 未実装")
    }

    suspend fun borrowLimit(uuid: UUID): Result<Double> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#borrowLimit 未実装")
    }

    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>> {
        // TODO: 実装を後続タスクで追加
        throw NotImplementedError("ServerLoanService#logs 未実装")
    }
}
