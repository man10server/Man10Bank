package red.man10.man10bank.service

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.VaultMoveDirection
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.api.model.response.MoneyLog
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.Messages
import java.util.UUID
import kotlin.math.abs

/**
 * BankApiClient 向けのサービス。
 * - 残高表示プロバイダの登録
 * - /deposit /withdraw の処理本体（電子マネー⇄銀行は単一トランザクションの move に委譲）
 */
class BankService(
    private val plugin: Man10Bank,
    private val api: BankApiClient,
    private val vaultService: VaultService,
    private val featureToggles: FeatureToggleService,
) {
    /** 残高表示プロバイダを登録（銀行）。 */
    fun registerBalanceProvider() {
        // 銀行残高はHTTP取得のみで context（Bukkit依存値）は不要。
        BalanceRegistry.register(id = "bank", order = 20) { player, _ ->
            val bal = getBalance(player)?: 0.0
            if (bal <= 0.0) "" else "§b§l銀行: ${BalanceFormats.coloredYen(bal)}§r"
        }
    }

    /**
     * Vault -> Bank 入金処理（メッセージ送信込み）。
     * 電子マネー→銀行を `POST /api/Vault/move` の単一トランザクションで移動する（VaultProvider 9）。
     * 旧来の「電子マネー引落し→銀行入金→失敗時返金」のクライアント側補償 Saga は廃止した。
     * 2 つの非冪等 POST に跨る補償が無くなるため、片側だけ確定する増殖/消失（timeout-but-success）が原理的に起きない。
     */
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
        // 電子マネー未接続中は中断（真実優先・fail-closed。確定応答を待てる move でも早期失敗で UX を保つ）。
        if (!vaultService.isReady()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        val result = vaultService.move(
            uuid = player.uniqueId,
            amount = amt,
            direction = VaultMoveDirection.VaultToBank,
            note = "PlayerDepositOnCommand",
            displayNote = "/depositによる入金",
        )
        if (result.isSuccess) {
            val res = result.getOrNull()
            Messages.send(plugin, player,
                "入金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(res?.bankBalance ?: 0.0)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(res?.vaultBalance ?: 0.0)}"
            )
        } else {
            val msg = result.errorMessage("入金に失敗しました。")
            Messages.error(plugin, player, "入金に失敗しました: $msg 金額: ${BalanceFormats.coloredYen(amt)}")
        }
    }

    /**
     * Bank -> Vault 出金処理（メッセージ送信込み）。
     * 銀行→電子マネーを `POST /api/Vault/move` の単一トランザクションで移動する。
     * 残高不足はサーバーが 409 を返し Result.failure になる（補償不要）。
     */
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
        // 電子マネー未接続中は中断（真実優先・fail-closed）。
        if (!vaultService.isReady()) {
            Messages.error(plugin, player, "電子マネーが利用できません。後でもう一度お試しください。")
            return
        }
        val result = vaultService.move(
            uuid = player.uniqueId,
            amount = amt,
            direction = VaultMoveDirection.BankToVault,
            note = "PlayerWithdrawOnCommand",
            displayNote = "/withdrawによる出金",
        )
        if (result.isSuccess) {
            val res = result.getOrNull()
            Messages.send(plugin, player,
                "出金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(res?.bankBalance ?: 0.0)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(res?.vaultBalance ?: 0.0)}"
            )
        } else {
            val msg = result.errorMessage("出金に失敗しました。")
            Messages.error(plugin, player, "出金に失敗しました: $msg")
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
    suspend fun resolveDepositAmount(player: Player, arg: String?): Double? {
        if (!vaultService.isReady()) return null
        // 「all」は電子マネー残高（キャッシュ即値）を上限に解決する。
        val vaultBal = vaultService.getBalance(player.uniqueId)
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


    private fun depositRequest(uuid: UUID, amount: Double, note: String, displayNote: String): DepositRequest =
        DepositRequest(
            uuid = uuid.toString(),
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
