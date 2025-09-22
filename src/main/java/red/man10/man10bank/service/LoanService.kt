package red.man10.man10bank.service

import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.LoanApiClient
import red.man10.man10bank.api.model.request.LoanCreateRequest
import red.man10.man10bank.api.model.response.Loan
import red.man10.man10bank.util.ItemStackBase64
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * プレイヤー間ローン機能のサービス（型のみ）。
 * - Listener を実装（イベントは後続で追加）
 * - ItemStack のBase64変換は ItemStackBase64 を利用
 */
class LoanService(
    private val plugin: Man10Bank,
    private val api: LoanApiClient,
) : Listener {

    /**
     * 借り手のローン取得（プレイヤー単位）。
     */
    suspend fun getBorrowerLoans(player: Player, limit: Int = 100, offset: Int = 0): Result<List<Loan>> =
        api.getBorrowerLoans(player.uniqueId, limit, offset)

    /**
     * ローン作成。
     * - 返却: 作成された Loan
     * - collateral は任意（null 可）
     */
    suspend fun create(
        lender: Player,
        borrower: Player,
        repayAmount: Double,
        paybackInDays: Int,
        collateral: ItemStack?,
    ): Result<Loan> {
        if (repayAmount <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。正の数を指定してください。"))
        if (paybackInDays <= 0) return Result.failure(IllegalArgumentException("返済期限日数が不正です。1以上を指定してください。"))

        val paybackDateIso = OffsetDateTime.now(ZoneOffset.UTC).plusDays(paybackInDays.toLong()).toString()
        val body = LoanCreateRequest(
            lendUuid = lender.uniqueId.toString(),
            borrowUuid = borrower.uniqueId.toString(),
            amount = repayAmount,
            paybackDate = paybackDateIso,
            collateralItem = encodeCollateralOrNull(collateral),
        )
        return api.create(body)
    }

    /**
     * ローン詳細の取得。
     */
    suspend fun get(id: Int): Result<Loan> = api.get(id)

    /**
     * 返済実行。
     * - collector は回収者（任意）。null の場合はAPIへ未指定で委譲
     */
    suspend fun repay(id: Int, collector: Player): Result<Loan> =
        api.repay(id, collector.uniqueId.toString())

    /**
     * 担保返却の解放。
     * - borrower は借り手（任意）。null の場合はAPIへ未指定で委譲
     */
    suspend fun releaseCollateral(id: Int, borrower: Player): Result<Loan> =
        api.releaseCollateral(id, borrower.uniqueId.toString())

    // --------------
    // 内部ユーティリティ
    // --------------
    /**
     * 担保アイテムをBase64へ変換（必要時に使用）。
     */
    internal fun encodeCollateralOrNull(item: ItemStack?): String? = item?.let { ItemStackBase64.encode(it) }
}
