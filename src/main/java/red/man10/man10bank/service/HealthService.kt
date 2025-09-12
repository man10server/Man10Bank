package red.man10.man10bank.service

import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.api.model.response.HealthPayload

/**
 * ヘルスチェック用サービス。
 * - シンプルな用途のため、インターフェイスは設けず具象クラスのみとします。
 */
class HealthService(
    private val client: HealthApiClient
) {
    /** ヘルス情報を取得します。 */
    suspend fun getHealth(): Result<HealthPayload> = client.get()

    /**
     * ヘルス情報を見やすい複数行のカラー付きメッセージに整形して返します。
     * - 色コードはMinecraftのフォーマットコード（§）を使用します。
     */
    fun formatHealthMessage(h: HealthPayload): String {
        val r = "§r"
        val gold = "§6"
        val aqua = "§b"
        val yellow = "§e"
        val green = "§a"
        val red = "§c"
        val db = if (h.database) "${green}成功${r}" else "${red}失敗${r}"
        return """
            ${gold}================== ${aqua}ヘルスチェック ${gold}==================$r
            ${yellow}サービス名   : ${aqua}${h.service}$r
            ${yellow}DB接続       : $db
            ${yellow}稼働時間     : ${aqua}${h.uptimeSeconds} 秒$r
            ${yellow}サーバー時刻 : ${aqua}${h.serverTimeUtc}$r
            ${yellow}起動時刻     : ${aqua}${h.startedAtUtc}$r
            ${gold}====================================================$r
        """.trimIndent()
    }
}
