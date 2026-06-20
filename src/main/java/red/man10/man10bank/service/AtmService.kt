package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.AtmApiClient
import red.man10.man10bank.api.model.request.AtmLogRequest
import red.man10.man10bank.api.model.response.AtmLog
import red.man10.man10bank.util.errorMessage
import java.util.UUID

/**
 * ATM関連のサービス層。
 * - API呼び出しの集約と、現金↔電子マネーの交換処理を提供します。
 *
 * 【一時停止中】現金⇔電子マネーの変換は、設計書 §11.4 の非同期 ATM 改修(Man10BankService の確定応答を
 * 待つ順序・冪等返金)の実装待ちのため停止しています。詳細は設計書 §15.3 を参照。
 * 旧実装は VaultManager の同期 deposit/withdraw に依存していたが、Vault Provider 化で電子マネーは
 * 非同期権威更新になったため、同期前提の旧フローはそのまま流用できません(誤った流用は増殖/消失リスク)。
 */
class AtmService(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val api: AtmApiClient,
    @Suppress("unused") private val vault: VaultManager,
    @Suppress("unused") private val cashItemManager: CashItemManager,
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
     * 現金アイテムを電子マネーへ換金する（設計書 §11.4）。
     * 【一時停止中】非同期 ATM 改修の実装待ちのため、現金・残高ともに一切動かさず案内のみ行う。
     */
    fun depositCashToVault(player: Player, stacks: Array<ItemStack>): Double {
        if (!plugin.server.isPrimaryThread) return 0.0
        player.sendMessage("§c[ATM] 電子マネー基盤の刷新中のため、現金のチャージは一時的に停止しています。")
        return 0.0
    }

    /**
     * 電子マネーを現金アイテムへ変換する（設計書 §11.4）。
     * 【一時停止中】非同期 ATM 改修の実装待ちのため、現金・残高ともに一切動かさず案内のみ行う。
     */
    fun withdrawVaultToCash(player: Player, amount: Double): Double {
        if (!plugin.server.isPrimaryThread) return 0.0
        player.sendMessage("§c[ATM] 電子マネー基盤の刷新中のため、現金化は一時的に停止しています。")
        return 0.0
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
