package red.man10.man10bank.service

import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerEstateApiClient
import red.man10.man10bank.api.model.response.ServerEstateHistory

/**
 * サーバー全体の資産(ServerEstate)に関するサービス。
 * - 履歴の取得
 * - 最新スナップショット（直近1件）の取得
 */
class ServerEstateService(
    private val plugin: Man10Bank,
    private val api: ServerEstateApiClient,
) {

    /** サーバー資産履歴の取得（失敗時は空リスト）。 */
    suspend fun history(limit: Int = 100, offset: Int = 0): List<ServerEstateHistory> {
        val res = api.history(limit, offset)
        if (res.isSuccess) return res.getOrNull().orEmpty()
        plugin.logger.warning(res.exceptionOrNull()?.message ?: "サーバー資産履歴の取得に失敗しました。")
        return emptyList()
    }

    /** 最新のServerEstate（直近の履歴1件）を返す。失敗時はnull。 */
    suspend fun latest(): ServerEstateHistory? {
        val res = api.history(limit = 1, offset = 0)
        if (res.isSuccess) return res.getOrNull()?.firstOrNull()
        plugin.logger.warning(res.exceptionOrNull()?.message ?: "サーバー資産情報の取得に失敗しました。")
        return null
    }
}

