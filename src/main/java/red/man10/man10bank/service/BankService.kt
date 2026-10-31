package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.api.error.IndeterminateOutcomeException
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.api.model.response.MoneyLog
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.Messages
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * BankApiClient 向けのサービス。
 * - 残高表示プロバイダの登録
 * - /deposit /withdraw /mpay の処理本体
 *
 * 結果不確定（タイムアウト等でサーバー応答を確認できない）の扱い:
 * - サーバー側で処理済みの可能性があるため、「失敗」とみなした補償（返金等）は行わない。
 *   行うとお金の増殖（入金: 電子マネー返金 + 銀行入金済み）や、二重送金の原因になる。
 * - 同一 requestId でサーバーへ再照会し（サーバーは同じキーに初回結果を返す）、確定成功/確定失敗が
 *   返ってから本来の後処理（電子マネー反映・返金・通知）を行う（[resolveLater]）。
 */
class BankService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: BankApiClient,
    private val vault: VaultManager,
    private val featureToggles: FeatureToggleService,
) {
    companion object {
        /** 結果不確定の取引を再照会する間隔と上限回数（15秒 × 20回 = 最長5分）。 */
        private const val RESOLVE_INTERVAL_MS = 15_000L
        private const val RESOLVE_MAX_ATTEMPTS = 20
        private const val RESOLVE_MAX_MINUTES = RESOLVE_MAX_ATTEMPTS * RESOLVE_INTERVAL_MS / 60_000
    }

    /** 結果不確定のまま解決待ちの取引（requestId -> 概要）。プラグイン停止時の要確認ログに使う。 */
    private val unresolved = ConcurrentHashMap<String, String>()

    /** 残高表示プロバイダを登録（銀行）。 */
    fun registerBalanceProvider() {
        // 銀行残高はHTTP取得のみで context（Bukkit依存値）は不要。
        BalanceRegistry.register(id = "bank", order = 20) { player, _ ->
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
        // Vault から引き落とし（Economy操作はメインスレッドへディスパッチ）
        val withdrew = vault.withdrawOnMain(player, amt)
        if (!withdrew) {
            Messages.error(plugin, player, "電子マネーからの引き落としに失敗しました。")
            return
        }
        // Bank へ入金（requestId は再照会でも同じものを使う）
        val req = depositRequest(
            player = player,
            amount = amt,
            note = "PlayerDepositOnCommand",
            displayNote = "/depositによる入金",
        )
        val result = api.deposit(req)
        if (result.isSuccess) {
            notifyDepositSuccess(player.uniqueId, amt, result.getOrNull() ?: 0.0)
            return
        }
        if (result.exceptionOrNull() is IndeterminateOutcomeException) {
            // 結果不確定: 銀行へ入金済みの可能性があるため電子マネーへは返金しない（返金すると増殖）。
            // 同一 requestId で再照会し、確定してから「成功通知」または「返金」を行う。
            val summary = "操作=deposit uuid=${player.uniqueId} 金額=${amt} note=${req.note}"
            plugin.logger.warning(
                "結果不確定[deposit] $summary requestId=${req.requestId} 詳細=サーバー応答を確認できなかったため返金せず再照会する"
            )
            Messages.warn(plugin, player,
                "入金の結果をサーバーに確認できませんでした。電子マネーからは引き落とし済みのため、" +
                        "結果が確定するまで自動で確認を続けます（最長${RESOLVE_MAX_MINUTES}分）。確定次第お知らせします。" +
                        "再度 /deposit を実行しないでください。"
            )
            resolveLater(
                requestId = req.requestId,
                summary = summary,
                attempt = { api.deposit(req) },
                onSuccess = { newBank -> notifyDepositSuccess(player.uniqueId, amt, newBank) },
                onFailure = { failed -> refundVaultForFailedDeposit(player.uniqueId, amt, failed.errorMessage()) },
            )
            return
        }
        // 確定失敗: 銀行には入金されていないので電子マネーへ返金する。
        refundVaultForFailedDeposit(player.uniqueId, amt, result.errorMessage())
    }

    private suspend fun notifyDepositSuccess(uuid: UUID, amt: Double, newBank: Double) {
        val vaultBalance = vault.getBalanceOnMain(plugin.server.getOfflinePlayer(uuid))
        notify(uuid,
            "入金に成功しました。" +
                    "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                    "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                    "§b電子マネー: ${BalanceFormats.coloredYen(vaultBalance)}"
        )
    }

    /**
     * 銀行入金が確定失敗した後の電子マネー返金。
     * Economy実装が例外を投げた場合も返金失敗(false)とみなし補償失敗処理へ進める
     * （例外を握らないと「電子マネーは減ったまま銀行入金も失敗」の消失が残る）。
     * 返金にも失敗した場合は運用追跡のため構造化ログを残す（DESIGN 3.6）。
     */
    private suspend fun refundVaultForFailedDeposit(uuid: UUID, amt: Double, reason: String) {
        val refunded = try {
            vault.depositOnMain(plugin.server.getOfflinePlayer(uuid), amt)
        } catch (t: Throwable) {
            plugin.logger.severe(
                "補償例外[deposit返金] uuid=${uuid} 金額=${amt} 操作=Vault返金 " +
                        "note=PlayerDepositOnCommand 詳細=銀行入金失敗後の電子マネー返金が例外で失敗: ${t.message}"
            )
            false
        }
        if (!refunded) {
            plugin.logger.severe(
                "補償失敗[deposit返金] uuid=${uuid} 金額=${amt} 操作=Vault返金 " +
                        "note=PlayerDepositOnCommand 詳細=銀行入金失敗後の電子マネー返金に失敗: ${reason}"
            )
            notify(uuid,
                "入金に失敗しました: $reason 金額: ${BalanceFormats.coloredYen(amt)}。さらに電子マネーへの返金にも失敗しました。管理者に連絡してください。",
                isError = true)
        } else {
            notify(uuid, "入金に失敗しました: $reason 金額: ${BalanceFormats.coloredYen(amt)}", isError = true)
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
        // 銀行から出金（requestId は再照会でも同じものを使う）
        val req = withdrawRequest(
            player = player,
            amount = amt,
            note = "PlayerWithdrawOnCommand",
            displayNote = "/withdrawによる出金",
        )
        val result = api.withdraw(req)
        if (result.isSuccess) {
            creditVaultAfterWithdraw(player.uniqueId, amt, result.getOrNull() ?: 0.0)
            return
        }
        if (result.exceptionOrNull() is IndeterminateOutcomeException) {
            // 結果不確定: 銀行から出金済みの可能性がある。失敗扱いで終えると「銀行だけ減った」消失になるため、
            // 同一 requestId で再照会し、確定してから電子マネーへ反映する。
            val summary = "操作=withdraw uuid=${player.uniqueId} 金額=${amt} note=${req.note}"
            plugin.logger.warning(
                "結果不確定[withdraw] $summary requestId=${req.requestId} 詳細=サーバー応答を確認できなかったため再照会で確定させる"
            )
            Messages.warn(plugin, player,
                "出金の結果をサーバーに確認できませんでした。結果が確定するまで自動で確認を続けます" +
                        "（最長${RESOLVE_MAX_MINUTES}分）。確定次第、電子マネーへ反映してお知らせします。" +
                        "再度 /withdraw を実行しないでください。"
            )
            resolveLater(
                requestId = req.requestId,
                summary = summary,
                attempt = { api.withdraw(req) },
                onSuccess = { newBank -> creditVaultAfterWithdraw(player.uniqueId, amt, newBank) },
                onFailure = { failed ->
                    notify(player.uniqueId, "出金は行われませんでした: ${failed.errorMessage("出金に失敗しました。")}", isError = true)
                },
            )
            return
        }
        Messages.error(plugin, player, "出金に失敗しました: ${result.errorMessage("出金に失敗しました。")}")
    }

    /**
     * 銀行出金が確定した後、電子マネーへ反映する。
     * Economy実装が例外を投げた場合も「入金失敗(false)」と同じく銀行へ返金する補償へ進める
     * （例外を握らないと返金ブロックを飛ばして「銀行は減ったまま電子マネー未反映」の消失が残る）。
     */
    private suspend fun creditVaultAfterWithdraw(uuid: UUID, amt: Double, newBank: Double) {
        val offline = plugin.server.getOfflinePlayer(uuid)
        val ok = try {
            vault.depositOnMain(offline, amt)
        } catch (t: Throwable) {
            plugin.logger.severe(
                "補償実行[withdraw] uuid=${uuid} 金額=${amt} 操作=Vault入金 " +
                        "note=PlayerWithdrawOnCommand 詳細=電子マネーへの入金が例外で失敗したため銀行へ返金する: ${t.message}"
            )
            false
        }
        if (ok) {
            notify(uuid,
                "出金に成功しました。" +
                        "§b金額: ${BalanceFormats.coloredYen(amt)} " +
                        "§b銀行残高: ${BalanceFormats.coloredYen(newBank)} " +
                        "§b電子マネー: ${BalanceFormats.coloredYen(vault.getBalanceOnMain(offline))}"
            )
            return
        }
        // 電子マネーへの入金に失敗したら銀行に返金
        notify(uuid, "出金は成功しましたが、電子マネーへの反映に失敗しました。銀行に返金します", isError = true)
        refundBankAfterFailedVaultCredit(uuid, amt)
    }

    /**
     * 電子マネー反映失敗時の銀行返金（補償）。
     * 結果不確定なら同一 requestId で再照会して確定させる（返金の二重実行を防ぐ）。
     * 確定失敗した場合は出金済みかつ電子マネー未反映の不整合が残るため構造化ログを残す（DESIGN 3.6）。
     */
    private suspend fun refundBankAfterFailedVaultCredit(uuid: UUID, amt: Double) {
        val req = depositRequest(uuid, amt, "RefundForFailedVaultDeposit", "電子マネーへの反映失敗による返金")
        val summary = "操作=withdraw返金 uuid=${uuid} 金額=${amt} note=${req.note}"
        val result = api.deposit(req)
        when {
            result.isSuccess ->
                notify(uuid, "返金に成功しました。銀行残高: ${BalanceFormats.coloredYen(result.getOrNull() ?: 0.0)}")
            result.exceptionOrNull() is IndeterminateOutcomeException -> {
                plugin.logger.warning(
                    "結果不確定[withdraw返金] $summary requestId=${req.requestId} 詳細=返金の応答を確認できなかったため再照会で確定させる"
                )
                notify(uuid, "返金の結果を確認中です。確定次第お知らせします。", isError = true)
                resolveLater(
                    requestId = req.requestId,
                    summary = summary,
                    attempt = { api.deposit(req) },
                    onSuccess = { newBank -> notify(uuid, "返金が確定しました。銀行残高: ${BalanceFormats.coloredYen(newBank)}") },
                    onFailure = { failed -> reportRefundFailure(uuid, amt, failed.errorMessage()) },
                )
            }
            else -> reportRefundFailure(uuid, amt, result.errorMessage())
        }
    }

    private fun reportRefundFailure(uuid: UUID, amt: Double, reason: String) {
        plugin.logger.severe(
            "補償失敗[withdraw返金] uuid=${uuid} 金額=${amt} 操作=銀行返金 " +
                    "note=RefundForFailedVaultDeposit 詳細=出金成功・電子マネー反映失敗後の銀行返金に失敗: ${reason}"
        )
        notify(uuid, "${BalanceFormats.coloredYen(amt)}円の返金に失敗しました。$reason 管理者に連絡してください。", isError = true)
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
            // 結果不確定の場合も errorMessage() が照会ID付きの文言を返す。管理者は残高を確認してから再実行する。
            val msg = result.errorMessage()
            Messages.error(plugin, sender, "残高調整に失敗しました: $msg")
        }
    }

    /**
     * /deposit 用の金額解決（null または "all" 相当をインジケータとして扱い、Vault残高を返す）。
     * 条件を満たさない場合は null。
     */
    suspend fun resolveDepositAmount(player: Player, arg: String?): Double? {
        if (!vault.isAvailable()) return null
        val vaultBal = vault.getBalanceOnMain(player)
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
     * - 結果不確定（タイムアウト等）は送金済みの可能性があるため失敗扱いにせず、同一 requestId で再照会して確定させる。
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
        val req = TransferRequest(
            fromUuid = sender.uniqueId.toString(),
            toUuid = targetUuid.toString(),
            amount = amt,
            pluginName = plugin.name,
            note = "TransferTo${targetName}",
            displayNote = "${targetName}へ送金",
            server = plugin.serverName,
            requestId = BankApiClient.newRequestId(),
        )
        val result = api.transfer(req)
        if (result.isSuccess) {
            notifyTransferSuccess(sender.uniqueId, sender.name, targetUuid, targetName, amt, result.getOrNull() ?: 0.0)
            return
        }
        if (result.exceptionOrNull() is IndeterminateOutcomeException) {
            // 結果不確定: 送金済みの可能性がある。失敗表示にするとプレイヤーが再実行して二重送金になるため、
            // 同一 requestId で再照会し、確定してから通知する。
            val summary = "操作=transfer from=${sender.uniqueId} to=${targetUuid} 金額=${amt}"
            plugin.logger.warning(
                "結果不確定[transfer] $summary requestId=${req.requestId} 詳細=サーバー応答を確認できなかったため再照会で確定させる"
            )
            Messages.warn(plugin, sender,
                "送金の結果をサーバーに確認できませんでした。送金済みの可能性があるため、結果が確定するまで自動で確認を続けます" +
                        "（最長${RESOLVE_MAX_MINUTES}分）。確定次第お知らせします。再度 /mpay を実行しないでください。"
            )
            resolveLater(
                requestId = req.requestId,
                summary = summary,
                attempt = { api.transfer(req) },
                onSuccess = { newBalance ->
                    notifyTransferSuccess(sender.uniqueId, sender.name, targetUuid, targetName, amt, newBalance)
                },
                onFailure = { failed ->
                    notify(sender.uniqueId, "送金は行われませんでした: ${failed.errorMessage("送金に失敗しました。")}", isError = true)
                },
            )
            return
        }

        // 失敗時は ApiHttpException.code（InsufficientFunds 等）で日本語化された文言を表示する。
        val msg = result.errorMessage("送金に失敗しました。")
        Messages.error(plugin, sender, "送金に失敗しました: $msg")
    }

    private fun notifyTransferSuccess(
        senderUuid: UUID,
        senderName: String,
        targetUuid: UUID,
        targetName: String,
        amt: Double,
        newBalance: Double,
    ) {
        notify(senderUuid,
            "送金に成功しました。送金先: $targetName 金額: ${BalanceFormats.coloredYen(amt)} " +
                    "§b銀行残高: ${BalanceFormats.coloredYen(newBalance)}"
        )
        // オンラインなら受取通知（銀行残高は省略）
        notify(targetUuid, "$senderName さんから ${BalanceFormats.coloredYen(amt)}の送金を受け取りました。", logIfOffline = false)
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

    /**
     * 結果不確定になった取引を、同一 requestId でサーバーへ再照会して確定させる。
     * - サーバーは同じ requestId の要求に初回の結果を返すため、再照会は二重実行にならない。
     * - 確定成功 → [onSuccess]（電子マネー反映・成功通知など）、確定失敗 → [onFailure]（返金・失敗通知など）。
     * - 上限回数まで確定しなければ要手動確認ログを残す。管理者はサーバーDBの idempotency_key / money_log で
     *   処理有無を確認できる。
     */
    private fun resolveLater(
        requestId: String,
        summary: String,
        attempt: suspend () -> Result<Double>,
        onSuccess: suspend (Double) -> Unit,
        onFailure: suspend (Result<Double>) -> Unit,
    ) {
        unresolved[requestId] = summary
        scope.launch {
            repeat(RESOLVE_MAX_ATTEMPTS) { index ->
                delay(RESOLVE_INTERVAL_MS)
                val result = attempt()
                if (result.isSuccess) {
                    unresolved.remove(requestId)
                    plugin.logger.info("結果確定[成功] $summary requestId=$requestId 再照会=${index + 1}回目")
                    onSuccess(result.getOrThrow())
                    return@launch
                }
                if (result.exceptionOrNull() !is IndeterminateOutcomeException) {
                    unresolved.remove(requestId)
                    plugin.logger.info("結果確定[失敗] $summary requestId=$requestId 再照会=${index + 1}回目 理由=${result.errorMessage()}")
                    onFailure(result)
                    return@launch
                }
            }
            unresolved.remove(requestId)
            plugin.logger.severe(
                "要手動確認[結果不確定] $summary requestId=$requestId " +
                        "詳細=再照会を${RESOLVE_MAX_ATTEMPTS}回行っても結果を確定できなかった。" +
                        "サーバーDBの idempotency_key / money_log で処理有無を確認すること"
            )
        }
    }

    /** プラグイン停止時に、未解決のまま残った結果不確定の取引をログへ残す（onDisable から呼ぶ）。 */
    fun logUnresolvedOnShutdown() {
        if (unresolved.isEmpty()) return
        unresolved.forEach { (requestId, summary) ->
            plugin.logger.severe(
                "要手動確認[結果不確定・未解決のまま停止] $summary requestId=$requestId " +
                        "詳細=サーバーDBの idempotency_key / money_log で処理有無を確認すること"
            )
        }
        unresolved.clear()
    }

    /**
     * 本人へ通知する。オンラインならメッセージ送信、オフラインなら（取引結果を追えるよう）ログに残す。
     * 再照会で確定するまでにログアウトしている場合があるため、Player を保持せず UUID から引き直す。
     */
    private fun notify(uuid: UUID, message: String, isError: Boolean = false, logIfOffline: Boolean = true) {
        if (!plugin.isEnabled) {
            plugin.logger.info("通知先へ送信できないためログに記録 uuid=$uuid: $message")
            return
        }
        plugin.server.scheduler.runTask(plugin, Runnable {
            val online = plugin.server.getPlayer(uuid)
            if (online == null) {
                if (logIfOffline) plugin.logger.info("通知先がオフラインのためログに記録 uuid=$uuid: $message")
                return@Runnable
            }
            if (isError) Messages.error(online, message) else Messages.send(online, message)
        })
    }

    /** 銀行残高の取得（エラー時はnull）。*/
    private suspend fun getBalance(player: Player): Double? =
        api.getBalance(player.uniqueId).getOrNull()


    private fun depositRequest(player: Player, amount: Double, note: String, displayNote: String): DepositRequest =
        depositRequest(player.uniqueId, amount, note, displayNote)

    private fun depositRequest(uuid: UUID, amount: Double, note: String, displayNote: String): DepositRequest =
        DepositRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
            requestId = BankApiClient.newRequestId(),
        )

    private fun withdrawRequest(player: Player, amount: Double, note: String, displayNote: String): WithdrawRequest =
        withdrawRequest(player.uniqueId, amount, note, displayNote)

    private fun withdrawRequest(uuid: UUID, amount: Double, note: String, displayNote: String): WithdrawRequest =
        WithdrawRequest(
            uuid = uuid.toString(),
            amount = amount,
            pluginName = plugin.name,
            note = note,
            displayNote = displayNote,
            server = plugin.serverName,
            requestId = BankApiClient.newRequestId(),
        )

    /**
     * 金額の正規化: 小数点以下を切り捨てて整数（Double）にする。
     * 例) 10.9 -> 10.0, 10.1 -> 10.0
     */
    private fun normalizeAmount(amount: Double): Double = amount.toLong().toDouble()
}
