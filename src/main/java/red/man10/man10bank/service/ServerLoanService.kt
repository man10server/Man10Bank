package red.man10.man10bank.service

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanLog
import red.man10.man10bank.api.model.response.PaymentInfoResponse
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage

/**
 * サーバーローン機能のサービス（型のみ）。
 * - ServerLoanApiClient を利用（実装は後続で追加）
 * - Listener は継承しない
 */
class ServerLoanService(
    private val plugin: Man10Bank,
    private val api: ServerLoanApiClient,
    private val featureToggles: FeatureToggleService,
) {

    /**
     * 残高表示プロバイダの登録（リボ関連）。
     * - リボの借入額
     * - 支払額
     * - 次の支払日
     * 以上3行を1つのProviderでまとめて表示します。
     */
    fun registerBalanceProvider() {
        BalanceRegistry.register(id = "serverloan", order = 30) { player ->
            val loan = get(player).getOrNull()
            val payInfo = paymentInfo(player).getOrNull()

            if (loan == null || loan.borrowAmount!! <= 0.0) {
                return@register ""
            }

            val borrowText = BalanceFormats.amount(loan.borrowAmount)
            val paymentText = loan.paymentAmount?.let { BalanceFormats.amount(it) } ?: "未設定"
            val nextDateText = payInfo?.nextRepayDate?.let { DateFormats.toDate(it) } ?: "不明"

            "§b§lまんじゅうリボ: §c§l${borrowText}円§r\n" +
            "§b§l支払額: §c§l${paymentText}円§r\n" +
            "§b§l次の支払日: §c§l${nextDateText}§r"
        }
    }

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
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) {
            Messages.error(plugin, player, "サーバーローン機能は現在停止中です。")
            return
        }
        if (amount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.borrow(player.uniqueId, ServerLoanBorrowBodyRequest(amount))
        if (result.isSuccess) {
            val loan = result.getOrNull()
            val paymentInfo = loan?.paymentAmount?.let { BalanceFormats.coloredYen(it) } ?: "未設定"
            Messages.send(plugin, player,
                "§a借入に成功しました。金額: ${BalanceFormats.coloredYen(amount)} §a支払額: $paymentInfo"
            )
        } else {
            val msg = result.errorMessage()
            Messages.error(plugin, player, "借入に失敗しました: $msg")
        }
    }

    /**
     * 返済処理（プレイヤー指定）。
     * - 引数: プレイヤーと金額
     * - 戻り値: なし（メッセージ送信は本メソッド内で行う）
     */
    suspend fun repay(player: Player, amount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) {
            Messages.error(plugin, player, "サーバーローン機能は現在停止中です。")
            return
        }
        if (amount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.repay(player.uniqueId, amount)
        if (result.isSuccess) {
            val loan = result.getOrNull()
            val remainingInfo = loan?.borrowAmount?.let { BalanceFormats.coloredYen(it) } ?: "不明"
            Messages.send(plugin, player, "§a返済に成功しました。金額: ${BalanceFormats.coloredYen(amount)} §a残額: $remainingInfo")
        } else {
            val msg = result.errorMessage()
            Messages.error(plugin, player, "返済に失敗しました: $msg")
        }
    }

    /**
     * 支払額の設定（プレイヤー指定）。
     * - paymentAmount が null の場合は未設定に戻す
     * - 正の数のみ許容
     */
    suspend fun setPaymentAmount(player: Player, paymentAmount: Double?) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) {
            Messages.error(plugin, player, "サーバーローン機能は現在停止中です。")
            return
        }
        if (paymentAmount != null && paymentAmount <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。正の数を指定してください。")
            return
        }
        val result = api.setPaymentAmount(player.uniqueId, paymentAmount)
        if (result.isSuccess) {
            val updated = result.getOrNull()
            val info = updated?.paymentAmount?.let { BalanceFormats.coloredYen(it) } ?: "未設定"
            Messages.send(plugin, player, "支払額を更新しました。支払額: $info")
        } else {
            val msg = result.errorMessage()
            Messages.error(plugin, player, "支払額の更新に失敗しました: $msg")
        }
    }

    /**
     * ログ取得（プレイヤー指定）。
     * - limit/offset によりページング可能
     */
    suspend fun logs(player: Player, limit: Int = 100, offset: Int = 0): Result<List<ServerLoanLog>> =
        api.logs(player.uniqueId, limit, offset)

    /**
     * 借入上限取得（プレイヤー指定）。
     */
    suspend fun borrowLimit(player: Player): Result<Double> = api.borrowLimit(player.uniqueId)

    /**
     * 支払情報取得（次回返済日/1日あたりの利息）。
     */
    suspend fun paymentInfo(player: Player): Result<PaymentInfoResponse> =
        api.paymentInfo(player.uniqueId)
}
