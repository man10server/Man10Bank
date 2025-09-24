package red.man10.man10bank.service

import org.bukkit.entity.Player
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
    private val api: EstateApiClient,
    private val vault: VaultManager,
    private val cashItemManager: CashItemManager,
) {

    /** プレイヤー自身の資産履歴を取得（失敗時は空リスト）。 */
    suspend fun history(player: Player, limit: Int = 100, offset: Int = 0): List<EstateHistory> {
        val res = api.history(player.uniqueId, limit, offset)
        if (res.isSuccess) return res.getOrNull().orEmpty()
        val msg = res.exceptionOrNull()?.message ?: "資産履歴の取得に失敗しました。"
        Messages.error(plugin, player, msg)
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

    /**
     * プレイヤーの現在資産をスナップショットとして送信。
     * - CashItemManager: インベントリ+エンダーチェストの現金合計
     * - VaultManager   : 電子マネー（Vault残高）
     */
    suspend fun snapshot(player: Player): Boolean {
        val cash = cashItemManager.countTotalCash(player)
        val vaultBal = vault.getBalance(player)
        val req = EstateUpdateRequest(
            cash = cash,
            vault = vaultBal,
            estateAmount = null,
            shop = null,
        )
        val res = api.snapshot(player.uniqueId, req)
        if (res.isSuccess) return res.getOrNull() == true
        val msg = res.exceptionOrNull()?.message ?: "資産スナップショットの送信に失敗しました。"
        Messages.error(plugin, player, msg)
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

