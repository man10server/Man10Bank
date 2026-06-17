package red.man10.man10bank.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.api.model.request.AtmLogRequest
import red.man10.man10bank.api.model.response.AtmLog
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import kotlin.math.floor
import java.util.UUID

/**
 * ATM関連のサービス層。
 * - API呼び出しの集約と、現金↔電子マネーの交換処理を提供します。
 *
 * 整合性方針（VaultProvider 4.6・真実優先 / fail-closed）:
 * - 電子マネーの入出金は楽観更新ではなく [VaultService.depositConfirmed]/[VaultService.withdrawConfirmed]
 *   で **サービス側の確定を待ってから** 現金アイテムを動かす。
 * - 「確定 → アイテム操作」の順を厳守し、確定できなければ現金は一切動かさない（増殖も消失もしない）。
 * - サービス未接続中（[VaultService.isReady] が false）は入出金を受け付けない。
 */
class AtmService(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val api: AtmApiClient,
    private val vaultService: VaultService,
    private val cashItemManager: CashItemManager,
) {
    /**
     * ATMログの取得。
     * - 成功時: ログのリストを返す
     * - 失敗時: 例外をスロー（ApiHttpException 等）
     */
    suspend fun logs(uuid: UUID, limit: Int = 100, offset: Int = 0): List<AtmLog> {
        val result = api.getLogs(uuid, limit, offset)
        return result.getOrElse { throw it }
    }

    /**
     * 現金アイテムを電子マネーへ入金する（確定応答方式。VaultProvider 4.6）。
     * - メインスレッドで現金を集計し、**clone して原本を 0 にすることで手元から確保(custody)** する。
     *   こうすることで、確定入金の待ち時間中にプレイヤーが現金を取り戻して二重取り（増殖）するのを防ぐ。
     * - サービスへ確定入金（REST await）し、成功で初めて現金を手放した扱いにする。
     * - 確定失敗（未接続/エラー）時は確保した現金をプレイヤーへ返却する（消失を防ぐ）。
     * - メインスレッド専用エントリ。非同期から呼ばれた場合は何もしない。
     */
    fun depositCashToVault(player: Player, stacks: Array<ItemStack>) {
        if (!plugin.server.isPrimaryThread) {
            plugin.logger.severe(
                "補償不要[ATM入金拒否] uuid=${player.uniqueId} 操作=電子マネー入金 " +
                        "note=ATMDeposit 詳細=メインスレッド外から呼び出されたため処理を中止した"
            )
            return
        }
        // 未接続中は受け付けない（現金は手元/GUIに残る）。
        if (!vaultService.isReady()) {
            Messages.error(plugin, player, "電子マネーに接続できないため入金できません。後でもう一度お試しください。")
            return
        }

        // 集計しつつ現金を確保（clone して原本を 0 にし、手元から取り上げる）。
        val captured = mutableListOf<ItemStack>()
        var total = 0.0
        for (stack in stacks) {
            if (stack.amount <= 0 || stack.type.isAir) continue
            val amountPerItem = cashItemManager.getAmountForItem(stack) ?: continue
            total += amountPerItem * stack.amount
            captured.add(stack.clone())
            stack.amount = 0
        }
        if (total <= 0.0) return
        total = floor(total)
        val uuid = player.uniqueId

        scope.launch {
            val result = vaultService.depositConfirmed(uuid, total, "ATMDeposit", "ATM入金")
            if (result.isSuccess) {
                // 確定済み。現金は確保済み（消費完了）なので、ログとメッセージのみ。
                logAtmAsync(player, total, true)
                Messages.send(plugin, player, "入金しました: ${BalanceFormats.coloredYen(total)}")
            } else {
                // 確定できなかったので確保した現金を返却する（消失を防ぐ）。
                // 注: 失敗時に電子マネーへ自動振替する救済は timeout-but-success で増殖し得るため行わない（真実優先）。
                val returned = runOnMain { giveBackCash(player, captured) }
                if (returned) {
                    plugin.logger.warning(
                        "補償[ATM入金返却] uuid=$uuid 金額=$total 操作=現金返却 " +
                                "note=ATMDeposit 詳細=電子マネー入金を確定できず現金を返却した: ${result.errorMessage()}"
                    )
                    Messages.error(plugin, player, "電子マネーに接続できないため入金できませんでした。現金を返却しました。")
                } else {
                    // オフライン等で返却できず。額面を残し手動復旧できるよう severe で記録する。
                    plugin.logger.severe(
                        "補償失敗[ATM入金返却] uuid=$uuid 金額=$total 操作=現金返却 " +
                                "note=ATMDeposit 詳細=入金確定失敗かつ現金を返却できず（対象オフライン等）。手動復旧が必要: ${result.errorMessage()}"
                    )
                }
            }
        }
    }

    /**
     * 電子マネーを現金アイテムへ出金する（確定応答方式。VaultProvider 4.6）。
     * - サービスへ確定出金（REST await・サーバー側で残高再チェック）してから現金を付与する。
     * - 付与しきれない分（インベントリ満杯）は確定入金で即時返金する。
     * - 確定失敗（未接続/残高不足）時は現金を一切付与しない。
     * - メインスレッド専用エントリ。
     *
     * @param onComplete 一連の処理完了後にメインスレッドで呼ぶコールバック（UI 再描画用・任意）。
     */
    fun withdrawVaultToCash(player: Player, amount: Double, onComplete: (() -> Unit)? = null) {
        if (!plugin.server.isPrimaryThread) {
            plugin.logger.severe(
                "補償不要[ATM出金拒否] uuid=${player.uniqueId} 金額=${amount} 操作=電子マネー出金 " +
                        "note=ATMWithdraw 詳細=メインスレッド外から呼び出されたため処理を中止した"
            )
            return
        }
        if (!vaultService.isReady()) {
            Messages.error(plugin, player, "電子マネーに接続できないため出金できません。後でもう一度お試しください。")
            return
        }
        if (amount <= 0.0) return
        // アイテム生成（未登録金種なら null）。生成できなければ電子マネーには一切触れない。
        val item = cashItemManager.getItemForAmount(amount) ?: return
        // 出金前にインベントリ空きを確認する。満杯なら出金しない
        // （「出金確定→現金を渡せず→返金も失敗」で消失する窓を、出金前に塞ぐ）。
        if (player.inventory.firstEmpty() == -1) {
            Messages.warn(plugin, player, "インベントリに空きがないため引き出せません。")
            return
        }
        val uuid = player.uniqueId

        scope.launch {
            val result = vaultService.withdrawConfirmed(uuid, amount, "ATMWithdraw", "ATM出金")
            if (result.isFailure) {
                Messages.warn(plugin, player, "残高不足、または電子マネーに接続できないため出金できませんでした。")
                runOnMain { onComplete?.invoke() }
                return@launch
            }

            // 確定出金成功。メインで現金を付与し、入りきらなかった分の金額を集計する
            // （事前チェック後にインベントリが埋まる稀なレースの保険）。
            val refundAmount = runOnMain {
                val leftovers = player.inventory.addItem(item)
                var refund = 0.0
                for (left in leftovers.values) {
                    val unit = cashItemManager.getAmountForItem(left) ?: continue
                    refund += unit * left.amount
                }
                refund
            }

            // 入りきらなかった分は確定入金で即時返金する。
            var refundFailed = false
            if (refundAmount > 0.0) {
                val refundResult = vaultService.depositConfirmed(uuid, refundAmount, "ATMWithdrawRefund", "ATM出金の返金（インベントリ満杯）")
                if (refundResult.isFailure) {
                    refundFailed = true
                    // 出金済みかつ現金未付与かつ返金失敗の不整合。構造化ログを残す（VaultProvider 11）。
                    plugin.logger.severe(
                        "補償失敗[ATM出金返金] uuid=$uuid 金額=$refundAmount 操作=電子マネー返金 " +
                                "note=ATMWithdrawRefund 詳細=現金が入りきらず返金にも失敗: ${refundResult.errorMessage()}"
                    )
                }
            }

            val granted = amount - refundAmount
            when {
                // 返金失敗時は成功メッセージを出さず、不整合を明示する（金額の誤解を防ぐ）。
                refundFailed ->
                    Messages.error(plugin, player, "${BalanceFormats.coloredYen(refundAmount)}分を引き出せず返金にも失敗しました。管理者に連絡してください。")
                granted > 0.0 -> {
                    logAtmAsync(player, granted, false)
                    Messages.send(plugin, player, "引き出しました: ${BalanceFormats.coloredYen(granted)}")
                }
                else ->
                    Messages.warn(plugin, player, "インベントリに空きがないため引き出せませんでした。")
            }
            runOnMain { onComplete?.invoke() }
        }
    }

    /**
     * 確保した現金をプレイヤーへ返却する（入りきらない分は足元へドロップ）。返却できたら true。
     * オフライン（受け取れない）なら false を返し、呼び出し側で手動復旧ログを残す
     * （オフライン者の足元に高額現金をドロップすると盗難リスクがあるため、ここではドロップしない）。
     * メインスレッド専用。
     */
    private fun giveBackCash(player: Player, items: List<ItemStack>): Boolean {
        if (items.isEmpty()) return true
        if (!player.isOnline) return false
        val leftovers = player.inventory.addItem(*items.toTypedArray())
        for (left in leftovers.values) {
            player.world.dropItemNaturally(player.location, left)
        }
        return true
    }

    /** [block] をメインスレッドで実行して結果を待つ（既にメインなら即実行）。 */
    private suspend fun <T> runOnMain(block: () -> T): T {
        if (plugin.server.isPrimaryThread) return block()
        val deferred = CompletableDeferred<T>()
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                deferred.complete(block())
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        })
        return deferred.await()
    }

    private fun logAtmAsync(player: Player, amount: Double, deposit: Boolean) {
        scope.launch(Dispatchers.IO) {
            val req = AtmLogRequest(
                uuid = player.uniqueId.toString(),
                amount = amount,
                deposit = deposit,
            )
            val result = api.appendLog(req)
            if (result.isFailure) {
                plugin.logger.warning("ATMログ送信に失敗しました: ${result.errorMessage()}")
            }
        }
    }
}
