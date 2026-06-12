package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.EstateApiClient
import red.man10.man10bank.api.model.request.EstateUpdateRequest
import red.man10.man10bank.api.model.response.Estate
import red.man10.man10bank.api.model.response.EstateHistory
import red.man10.man10bank.util.Messages

/**
 * プレイヤー資産(エステート)関連のサービス。
 * - history/ranking は EstateHistory のリストを返す
 * - snapshot は Player を引数に、現金/電子マネー(Vault)の現在値をAPIへ送信
 */
class EstateService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: EstateApiClient,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
    private val chequeService: ChequeService,
) : Listener {

    /** プレイヤー自身の資産履歴を取得（失敗時は空リスト）。 */
    suspend fun history(player: Player, limit: Int = 100, offset: Int = 0): List<EstateHistory> {
        val res = api.history(player.uniqueId, limit, offset)
        if (res.isSuccess) return res.getOrNull().orEmpty()
        val msg = res.exceptionOrNull()?.message ?: "資産履歴の取得に失敗しました。"
        Messages.error(plugin, player, msg)
        return emptyList()
    }

    /** UUID指定で資産履歴を取得（失敗時は空リスト）。 */
    suspend fun history(uuid: java.util.UUID, limit: Int = 100, offset: Int = 0): List<EstateHistory> {
        val res = api.history(uuid, limit, offset)
        if (res.isSuccess) return res.getOrNull().orEmpty()
        plugin.logger.warning(res.exceptionOrNull()?.message ?: "資産履歴の取得に失敗しました。")
        return emptyList()
    }

    /** サーバー全体の資産ランキング（履歴と同じ形で返す）。失敗時は空リスト。 */
    suspend fun ranking(limit: Int = 100, offset: Int = 0): List<EstateHistory> {
        val res = api.ranking(limit, offset)
        if (res.isSuccess) {
            val estates = res.getOrNull().orEmpty()
            return estates.map { it.asHistory() }
        }
        // ランキングはプレイヤー対象でないため、コンソールにもログしておく
        val msg = res.exceptionOrNull()?.message ?: "資産ランキングの取得に失敗しました。"
        plugin.logger.warning(msg)
        return emptyList()
    }

    // -----------------
    // イベント連携（ログイン/ログアウトでスナップショット）
    // -----------------
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        // イベントハンドラ（メインスレッド）で Bukkit/Vault 依存値を収集してから非同期送信する（DESIGN 3.5）。
        dispatchSnapshot(e.player)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        dispatchSnapshot(e.player)
    }

    /**
     * メインスレッドで Bukkit/Vault 依存の資産値（現金・電子マネー・小切手合計）を収集し、
     * HTTP 送信のみを非メインスレッドで実行する（DESIGN 3.5）。
     */
    private fun dispatchSnapshot(player: Player) {
        val uuid = player.uniqueId
        val cash = cashItemManager.countTotalCash(player)
        val vaultBal = vault.getBalance(player)
        val chequeTotal = chequeService.countTotalChequeAmount(player)
        scope.launch { snapshot(uuid, cash, vaultBal, chequeTotal) }
    }

    /**
     * プレイヤーの現在資産をスナップショットとして送信（HTTPのみ。Bukkit API には触れない）。
     * - cash       : インベントリ+エンダーチェストの現金合計（メインスレッドで収集済み）
     * - vaultBal   : 電子マネー（Vault残高、メインスレッドで収集済み）
     * - chequeTotal: 小切手合計（メインスレッドで収集済み）
     */
    private suspend fun snapshot(
        uuid: java.util.UUID,
        cash: Double,
        vaultBal: Double,
        chequeTotal: Double,
    ): Boolean {
        val req = EstateUpdateRequest(
            cash = cash,
            vault = vaultBal,
            estateAmount = chequeTotal,
            shop = null,
        )
        val res = api.snapshot(uuid, req)
        if (res.isSuccess) return res.getOrNull() == true
        // 失敗時はログを残す（DESIGN 3.5: snapshot 失敗を握りつぶさない）。
        plugin.logger.warning("資産スナップショットの送信に失敗しました: uuid=${uuid} ${res.exceptionOrNull()?.message ?: ""}")
        return false
    }

    private fun Estate.asHistory(): EstateHistory =
        EstateHistory(
            id = id,
            player = player,
            uuid = uuid,
            date = date,
            vault = vault,
            bank = bank,
            cash = cash,
            estateAmount = estateAmount,
            loan = loan,
            shop = shop,
            crypto = crypto,
            total = total,
        )
}
