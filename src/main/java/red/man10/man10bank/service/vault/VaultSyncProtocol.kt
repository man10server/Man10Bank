package red.man10.man10bank.service.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Vault 同期 WebSocket のメッセージ表現とエンコード/デコード（VaultProvider 5.3/5.4）。
 * ソケット I/O から切り離して単体テスト可能にする。
 */
object VaultSyncProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** サーバー → クライアントの受信イベント。type により balance / ping 等を区別する。 */
    @Serializable
    data class ServerEvent(
        val type: String? = null,
        val uuid: String? = null,
        val balance: Double? = null,
        val version: Long? = null,
        val cause: String? = null,
        val originServer: String? = null,
        val ts: String? = null,
    )

    @Serializable
    private data class PresenceMessage(
        val type: String = "presence",
        val action: String,
        val uuid: String,
        val server: String,
    )

    /** 受信テキストを [ServerEvent] へデコードする（失敗時は null）。 */
    fun decode(text: String): ServerEvent? =
        runCatching { json.decodeFromString(ServerEvent.serializer(), text) }.getOrNull()

    /** presence(join/quit) メッセージの JSON。 */
    fun presence(action: String, uuid: String, server: String): String =
        json.encodeToString(PresenceMessage.serializer(), PresenceMessage(action = action, uuid = uuid, server = server))

    /** ハートビート応答。 */
    fun pong(): String = "{\"type\":\"pong\"}"
}
