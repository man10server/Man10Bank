package red.man10.man10bank.service

import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.api.model.request.ServerLoanBorrowBodyRequest
import red.man10.man10bank.api.model.response.ServerLoan
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.api.model.response.ServerLoanLog
import red.man10.man10bank.api.model.response.PaymentInfoResponse
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.Messages
import java.util.UUID

/**
 * サーバーローン（リボ、/mrevo）のサービス。
 * 借入・返済・借入枠の計算などは銀行サーバーが行い、ここは API の呼び出しとプレイヤーへの結果の通知を受け持つ。
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
        // リボ情報はHTTP取得のみで context（Bukkit依存値）は不要。
        BalanceRegistry.register(id = "serverloan", order = 30) { target, _ ->
            val loan = get(target.uniqueId).getOrNull()
            val payInfo = paymentInfo(target.uniqueId).getOrNull()

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
    suspend fun get(player: Player): Result<ServerLoan> = get(player.uniqueId)

    /** UUID から取得（/bankop lookup などオフラインの対象にも使う）。 */
    suspend fun get(uuid: UUID): Result<ServerLoan> = api.get(uuid)

    /** 返済情報を UUID から取得。 */
    suspend fun paymentInfo(uuid: UUID): Result<PaymentInfoResponse> = api.paymentInfo(uuid)

    /**
     * 借入処理（プレイヤー指定）。
     * - 引数: プレイヤーと金額
     * - 戻り値: なし（メッセージ送信は本メソッド内で行う）
     */
    suspend fun borrow(player: Player, amount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) {
            Messages.error(plugin, player, "Man10リボは現在停止中です。")
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
            Messages.error(plugin, player, "Man10リボは現在停止中です。")
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
     * - 正の数のみ許容
     */
    suspend fun setPaymentAmount(player: Player, paymentAmount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) {
            Messages.error(plugin, player, "Man10リボは現在停止中です。")
            return
        }
        if (paymentAmount <= 0.0) {
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
     * 借入額の設定（管理者/運営用途）。
     * - amount は 0 円以上を許容
     * - 小数点以下は切り捨てて API へ送信
     * - 機能トグルの影響を受けない
     */
    suspend fun setBorrowAmount(uuid: UUID, amount: Double): Result<ServerLoan> {
        if (amount < 0.0) {
            return Result.failure(IllegalArgumentException("金額が不正です。0円以上を指定してください。"))
        }
        val normalized = amount.toLong().toDouble()
        return api.setBorrowAmount(uuid, normalized)
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

    // ===== 契約の共有（リンク） =====

    /** リボが停止中ならプレイヤーに知らせて false を返す。 */
    fun ensureEnabled(player: Player): Boolean {
        if (featureToggles.isEnabled(FeatureToggleService.Feature.SERVER_LOAN)) return true
        Messages.error(plugin, player, "Man10リボは現在停止中です。")
        return false
    }

    /** uuid が使っている契約と、共有の状態（参加者・承認待ちの申請）。 */
    suspend fun contract(uuid: UUID): Result<ServerLoanContract> = api.contract(uuid)

    /** uuid が target の契約への参加を申請する。 */
    suspend fun requestLink(uuid: UUID, target: UUID): Result<ServerLoanContract> = api.requestLink(uuid, target)

    /** 本垢 uuid が、member からの参加申請を承認する。 */
    suspend fun approveLink(uuid: UUID, member: UUID): Result<ServerLoanContract> = api.approveLink(uuid, member)

    /** 本垢 uuid が、member からの参加申請を拒否する。 */
    suspend fun rejectLink(uuid: UUID, member: UUID): Result<ServerLoanContract> = api.rejectLink(uuid, member)

    /** uuid が、自分の出した参加申請を取り消す。 */
    suspend fun cancelLinkRequest(uuid: UUID): Result<ServerLoanContract> = api.cancelLinkRequest(uuid)

    /** uuid が、参加している契約から脱退する（契約に残債がある間は不可）。 */
    suspend fun leaveLink(uuid: UUID): Result<ServerLoanContract> = api.leaveLink(uuid)

    /** 運営用: uuid を owner の契約に申請・承認なしで参加させる（機能トグルの影響を受けない）。 */
    suspend fun forceLink(uuid: UUID, owner: UUID, operator: String, resetLimit: Boolean): Result<ServerLoanContract> =
        api.forceLink(uuid, owner, operator.take(OPERATOR_NAME_MAX_LENGTH), resetLimit)

    /** 運営用: uuid の参加（または承認待ちの申請）を取り消す（機能トグルの影響を受けない）。 */
    suspend fun forceUnlink(uuid: UUID, operator: String): Result<ServerLoanContract> =
        api.forceUnlink(uuid, operator.take(OPERATOR_NAME_MAX_LENGTH))

    private companion object {
        // 銀行サーバーが受け付ける操作者名の長さ（server_loan_member.linked_by）
        const val OPERATOR_NAME_MAX_LENGTH = 16
    }
}
