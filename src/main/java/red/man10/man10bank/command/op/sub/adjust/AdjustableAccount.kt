package red.man10.man10bank.command.op.sub.adjust

import red.man10.man10bank.Man10Bank
import red.man10.man10bank.service.BankService
import red.man10.man10bank.service.PlayerLookupService.ResolvedPlayer
import red.man10.man10bank.service.ServerLoanService
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.runOnMainThread

/**
 * /bankop give|take|set で操作できる口座の抽象。
 * - 現在値の取得と、[AdjustPlan]（変更前後の値）に基づく変更を提供する。
 */
interface AdjustableAccount {
    /** コマンド引数のキー（bank / vault / serverloan）。 */
    val key: String
    /** メッセージ用の名称（銀行残高 など）。 */
    val labelJa: String
    /** 減算で 0 未満になる場合に 0 で止めるか（false なら残高不足として失敗）。 */
    val floorAtZero: Boolean
    /** 事前条件を満たさない場合はその理由（例: Vault 未接続）。満たしていれば null。 */
    fun unavailableReason(): String? = null
    /** 現在値を取得する。 */
    suspend fun current(target: ResolvedPlayer): Result<Double>
    /** 変更を適用し、変更後の値を返す。 */
    suspend fun apply(actor: String, target: ResolvedPlayer, verb: AdjustVerb, plan: AdjustPlan, reason: String): Result<Double>
}

/** 銀行残高。差分を管理者入金／出金として記録する（取引トグルの影響を受けない）。 */
class BankAccount(private val bankService: BankService) : AdjustableAccount {
    override val key = "bank"
    override val labelJa = "銀行残高"
    override val floorAtZero = false

    override suspend fun current(target: ResolvedPlayer): Result<Double> = bankService.getBalance(target.uuid)

    override suspend fun apply(actor: String, target: ResolvedPlayer, verb: AdjustVerb, plan: AdjustPlan, reason: String): Result<Double> {
        val note = "Admin${verb.name.lowercase().replaceFirstChar { it.uppercase() }}By$actor"
        val displayNote = buildString {
            append("管理者(").append(actor).append(")による").append(labelJa).append(verb.labelJa)
            if (reason.isNotBlank()) append(": ").append(reason)
        }.take(DISPLAY_NOTE_MAX)
        return when {
            plan.delta > 0.0 -> bankService.adminDeposit(target.uuid, plan.delta, note, displayNote)
            plan.delta < 0.0 -> bankService.adminWithdraw(target.uuid, -plan.delta, note, displayNote)
            else -> Result.success(plan.after)
        }
    }

    companion object {
        /** サーバー側 displayNote の最大長（StringLength(64)）。 */
        private const val DISPLAY_NOTE_MAX = 64
    }
}

/** 電子マネー（Vault）。Economy 操作はメインスレッドで行う。 */
class VaultAccount(private val plugin: Man10Bank, private val vaultManager: VaultManager) : AdjustableAccount {
    override val key = "vault"
    override val labelJa = "電子マネー"
    override val floorAtZero = false

    override fun unavailableReason(): String? =
        if (vaultManager.isAvailable()) null else "電子マネーが利用できません。Vault連携を確認してください。"

    override suspend fun current(target: ResolvedPlayer): Result<Double> = runCatching {
        plugin.runOnMainThread { vaultManager.getBalance(plugin.server.getOfflinePlayer(target.uuid)) }
    }

    override suspend fun apply(actor: String, target: ResolvedPlayer, verb: AdjustVerb, plan: AdjustPlan, reason: String): Result<Double> {
        val offline = plugin.runOnMainThread { plugin.server.getOfflinePlayer(target.uuid) }
        val ok = when {
            plan.delta > 0.0 -> vaultManager.depositOnMain(offline, plan.delta)
            plan.delta < 0.0 -> vaultManager.withdrawOnMain(offline, -plan.delta)
            else -> true
        }
        if (!ok) return Result.failure(IllegalStateException("電子マネーの操作に失敗しました（Economy実装が拒否しました）"))
        return Result.success(vaultManager.getBalanceOnMain(offline))
    }
}

/** サーバーローン借入額。減算で 0 未満になる場合は完済（0）として扱う。 */
class ServerLoanAccount(private val serverLoanService: ServerLoanService) : AdjustableAccount {
    override val key = "serverloan"
    override val labelJa = "サーバーローン借入額"
    override val floorAtZero = true

    override suspend fun current(target: ResolvedPlayer): Result<Double> =
        serverLoanService.get(target.uuid).map { it.borrowAmount ?: 0.0 }

    override suspend fun apply(actor: String, target: ResolvedPlayer, verb: AdjustVerb, plan: AdjustPlan, reason: String): Result<Double> =
        serverLoanService.setBorrowAmount(target.uuid, plan.after).map { it.borrowAmount ?: plan.after }
}
