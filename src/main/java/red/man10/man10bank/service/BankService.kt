package red.man10.man10bank.service

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.api.model.response.MoneyLog
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.Messages
import java.util.UUID
import kotlin.math.abs

/**
 * BankApiClient 向けのサービス。
 * - 残高表示プロバイダの登録
 * - /deposit /withdraw の処理本体
 */
class BankService(
    private val plugin: Man10Bank,
    private val api: BankApiClient,
    private val vault: VaultManager,
    private val featureToggles: FeatureToggleService,
) {
    /** 残高表示プロバイダを登録（銀行）。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(id = "bank", order = 20) { player ->
            val bal = getBalance(player)?: 0.0
            if (bal <= 0.0) "" else "§b§l銀行: ${BalanceFormats.coloredYen(bal)}§r"
        }
    }

    /** Vault -> Bank 入金処理（メッセージ送信込み）。 */
    suspend fun deposit(player: Player, amount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.TRANSACTION)) {
            Messages.error(plugin, player, "取引機能（入金/出金/送金）は現在停止中です。")
            return
        }
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。1円以上を指定してください。")
            return
        }
        // 電子マネー(Vault) 未接続の場合は安全のため中断
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        // Vault から引き落とし
        val withdrew = vault.withdraw(player, amt)
        if (!withdrew) {
            Messages.error(plugin, player, "電子マネーからの引き落としに失敗しました。")
            return
        }
        // Bank へ入金
        val result = api.deposit(
            depositRequest(
                player = player,
                amount = amt,
                note = "PlayerDepositOnCommand",
                displayNote = "/depositによる入金",
            )
        )
        if (result.isSuccess) {
            val newBank = result.getOrNull() ?: 0.0
            Messages.send(plugin, player,
                "入金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(vault.getBalance(player))}"
            )
            return
        }
        // 失敗したので 電子マネー に返金
        val refunded = vault.deposit(player, amt)
        val msg = result.errorMessage()
        if (!refunded) {
            // 補償（電子マネーへの返金）にも失敗。運用追跡のため構造化ログを残す（DESIGN 3.6）。
            plugin.logger.severe(
                "補償失敗[deposit返金] uuid=${player.uniqueId} 金額=${amt} 操作=Vault返金 " +
                        "note=PlayerDepositOnCommand 詳細=銀行入金失敗後の電子マネー返金に失敗: ${msg}"
            )
            Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.coloredYen(amt)}。さらに電子マネーへの返金にも失敗しました。管理者に連絡してください。")
        } else {
            Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.coloredYen(amt)}")
        }
    }

    /** Bank -> Vault 出金処理（メッセージ送信込み）。 */
    suspend fun withdraw(player: Player, amount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.TRANSACTION)) {
            Messages.error(plugin, player, "取引機能（入金/出金/送金）は現在停止中です。")
            return
        }
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, player, "金額が不正です。1円以上を指定してください。")
            return
        }
        // 電子マネー(Vault) 未接続の場合は安全のため中断（銀行からの出金を行わない）
        if (!vault.isAvailable()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        // 銀行から出金
        val result = api.withdraw(
            withdrawRequest(
                player = player,
                amount = amt,
                note = "PlayerWithdrawOnCommand",
                displayNote = "/withdrawによる出金",
            )
        )

        if (!result.isSuccess) {
            val msg = result.errorMessage("出金に失敗しました。")
            Messages.error(plugin, player, "出金に失敗しました: $msg")
            return
        }

        val newBank = result.getOrNull() ?: 0.0

        // Vault に入金
        val ok = vault.deposit(player, amt)
        if (ok) {
            Messages.send(
                plugin,
                player,
                "出金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(vault.getBalance(player))}"
            )
            return
        }

        // 電子マネーへの入金に失敗したら銀行に返金
        Messages.error(plugin, player, "出金は成功しましたが、電子マネーへの反映に失敗しました。銀行に返金します")
        val refundResult = api.deposit(
            depositRequest(
                player = player,
                amount = amt,
                note = "RefundForFailedVaultDeposit",
                displayNote = "電子マネーへの反映失敗による返金",
            )
        )
        if (refundResult.isSuccess) {
            Messages.send(plugin, player, "返金に成功しました。銀行残高: ${BalanceFormats.coloredYen(refundResult.getOrNull() ?: 0.0)}")
        } else {
            // 補償（銀行への返金）に失敗。出金済みかつ電子マネー未反映の不整合が残るため構造化ログを残す（DESIGN 3.6）。
            val msg = refundResult.errorMessage()
            plugin.logger.severe(
                "補償失敗[withdraw返金] uuid=${player.uniqueId} 金額=${amt} 操作=銀行返金 " +
                        "note=RefundForFailedVaultDeposit 詳細=出金成功・電子マネー反映失敗後の銀行返金に失敗: ${msg}"
            )
            Messages.error(plugin, player, "${BalanceFormats.coloredYen(amt)}円の返金に失敗しました。$msg")
        }
    }

    /**
     * 管理者用: 指定プレイヤーの銀行残高を指定額に調整する。
     * - オフラインでも実行可能
     * - 取引トグルの影響を受けない（管理者操作）
     */
    suspend fun setBalance(
        sender: CommandSender,
        targetUuid: UUID,
        targetName: String,
        amount: Double,
        reason: String,
    ) {
        if (amount < 0.0) {
            Messages.error(plugin, sender, "金額が不正です。0円以上を指定してください。")
            return
        }
        val targetAmount = normalizeAmount(amount)
        val currentResult = api.getBalance(targetUuid)
        if (currentResult.isFailure) {
            Messages.error(plugin, sender, "残高取得に失敗しました: ${currentResult.errorMessage()}")
            return
        }
        val current = currentResult.getOrNull() ?: 0.0
        if (targetAmount == current) {
            Messages.send(plugin, sender, "残高は既に ${BalanceFormats.coloredYen(current)} です。")
            return
        }

        val diff = targetAmount - current
        val actor = sender.name
        val reasonText = reason.trim()
        val displayNote = if (reasonText.isBlank()) {
            "管理者(${actor})による残高調整"
        } else {
            "管理者(${actor})による残高調整: $reasonText"
        }
        val note = "AdminSetBalanceBy${actor}"

        val result = if (diff > 0.0) {
            api.deposit(depositRequest(targetUuid, diff, note, displayNote))
        } else {
            api.withdraw(withdrawRequest(targetUuid, abs(diff), note, displayNote))
        }

        if (result.isSuccess) {
            val newBalance = result.getOrNull() ?: targetAmount
            val diffText = if (diff >= 0.0) {
                "+${BalanceFormats.coloredYen(diff)}"
            } else {
                "-${BalanceFormats.coloredYen(abs(diff))}"
            }
            Messages.send(
                plugin,
                sender,
                "残高を調整しました。対象: $targetName 変更: $diffText 変更後: ${BalanceFormats.coloredYen(newBalance)} 理由: $reasonText"
            )
        } else {
            val msg = result.errorMessage()
            Messages.error(plugin, sender, "残高調整に失敗しました: $msg")
        }
    }

    /**
     * /deposit 用の金額解決（null または "all" 相当をインジケータとして扱い、Vault残高を返す）。
     * 条件を満たさない場合は null。
     */
    fun resolveDepositAmount(player: Player, arg: String?): Double? {
        if (!vault.isAvailable()) return null
        val vaultBal = vault.getBalance(player)
        val amount = if (arg.isNullOrBlank() || arg.equals("all", ignoreCase = true)) vaultBal else arg.toDoubleOrNull() ?: -1.0
        if (amount > vaultBal) return null
        return if (amount > 0.0) amount else null
    }

    /**
     * /withdraw 用の金額解決（null または "all" 相当で銀行残高）。条件を満たさない場合は null。
     */
    suspend fun resolveWithdrawAmount(player: Player, arg: String?): Double? {
        val bankBal = getBalance(player) ?: return null
        val amount = if (arg.isNullOrBlank() || arg.equals("all", ignoreCase = true)) bankBal else arg.toDoubleOrNull() ?: -1.0
        if (amount > bankBal) return null
        return if (amount > 0.0) amount else null
    }

    /**
     * 振り込み（送金）処理。
     * - サーバー側の単一トランザクションで「送金元出金＋送金先入金＋MoneyLog2件」を処理する
     *   `POST /api/Bank/transfer`（DESIGN 1.5/3.3）に委譲する。
     * - クライアント側の出金→入金→返金補償ロジックは廃止した（部分失敗が原理的に起きない）。
     * - 失敗時は ApiHttpException の ProblemDetails.code で種別を判定し日本語メッセージを表示する。
     */
    suspend fun transfer(sender: Player, targetUuid: UUID, targetName: String, amount: Double) {
        if (!featureToggles.isEnabled(FeatureToggleService.Feature.TRANSACTION)) {
            Messages.error(plugin, sender, "取引機能（入金/出金/送金）は現在停止中です。")
            return
        }
        // 金額は小数点以下を切り捨て（整数化）
        val amt = normalizeAmount(amount)
        if (amt <= 0.0) {
            Messages.error(plugin, sender, "金額が不正です。正の数を指定してください。")
            return
        }
        if (sender.uniqueId == targetUuid) {
            Messages.error(plugin, sender, "自分自身へは送金できません。")
            return
        }

        // サーバーへ送金を委譲（単一トランザクション）。成功時は送金元の新残高が返る。
        val result = api.transfer(
            TransferRequest(
                fromUuid = sender.uniqueId.toString(),
                toUuid = targetUuid.toString(),
                amount = amt,
                pluginName = plugin.name,
                note = "TransferTo${targetName}",
                displayNote = "${targetName}へ送金",
                server = plugin.serverName,
            )
        )

        if (result.isSuccess) {
            val newBalance = result.getOrNull() ?: 0.0
            Messages.send(plugin, sender,
                "送金に成功しました。送金先: $targetName 金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBalance)}"
            )
            // オンラインなら受取通知（銀行残高は省略）
            plugin.server.getPlayer(targetUuid)?.let {
                Messages.send(plugin, it, "${sender.name} さんから ${BalanceFormats.coloredYen(amt)}の送金を受け取りました。")
            }
            return
        }

        // 失敗時は ApiHttpException.code（InsufficientFunds 等）で日本語化された文言を表示する。
        val msg = result.errorMessage("送金に失敗しました。")
        Messages.error(plugin, sender, "送金に失敗しました: $msg")
    }

    /**
     * 取引ログの取得（失敗時はProblemDetailsを表示し、nullを返す）。
     */
    suspend fun getLogs(player: Player, limit: Int = 10, offset: Int = 0): List<MoneyLog>? {
        val result = api.getLogs(player.uniqueId, limit, offset)
        if (result.isSuccess) return result.getOrNull()
        val msg = result.errorMessage()
        Messages.error(plugin, player, "ログ取得に失敗しました: $msg")
        return null
    }

    /** 銀行残高の取得（エラー時はnull）。*/
    private suspend fun getBalance(player: Player): Double? =
        api.getBalance(player.uniqueId).getOrNull()


    private fun depositRequest(player: Player, amount: Double, note: String, displayNote: String): DepositRequest =
        DepositRequest(
            uuid = player.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    private fun depositRequest(uuid: UUID, amount: Double, note: String, displayNote: String): DepositRequest =
        DepositRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    private fun withdrawRequest(player: Player, amount: Double, note: String, displayNote: String): WithdrawRequest =
        WithdrawRequest(
            uuid = player.uniqueId.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    private fun withdrawRequest(uuid: UUID, amount: Double, note: String, displayNote: String): WithdrawRequest =
        WithdrawRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
        )

    /**
     * 金額の正規化: 小数点以下を切り捨てて整数（Double）にする。
     * 例) 10.9 -> 10.0, 10.1 -> 10.0
     */
    private fun normalizeAmount(amount: Double): Double = amount.toLong().toDouble()
}
