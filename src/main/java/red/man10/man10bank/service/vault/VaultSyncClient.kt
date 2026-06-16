package red.man10.man10bank.service.vault

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import red.man10.man10bank.Man10Bank
import java.net.URLEncoder
import java.util.UUID

/**
 * サービスへの Vault 同期 WebSocket 接続（VaultProvider 5.2/5.5）。
 *
 * - 起動時に 1 本張り、presence(join/quit) 送信と残高イベント受信を行う。
 * - 残高イベントは version 方式でキャッシュへ適用する（[VaultCache.applyAuthoritative]）。
 * - 切断時は指数バックオフで再接続し、再接続後に在席者を presence 再登録＋全員 full resync する。
 * - 接続状態は [VaultService.setConnected] へ反映する（Economy.isEnabled に使う）。
 *
 * 認証は共有 [HttpClient] の DefaultRequest が付与する Bearer を upgrade リクエストに流用する。
 */
class VaultSyncClient(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val client: HttpClient,
    private val service: VaultService,
    private val baseUrl: String,
    private val serverName: String,
) {
    @Volatile
    private var session: WebSocketSession? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    connectAndRun()
                    backoffMs = 1_000L // 正常にセッションを終えたらバックオフをリセット
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    plugin.logger.warning("Vault同期WebSocketが切断されました: ${e.message}")
                } finally {
                    service.setConnected(false)
                    session = null
                }
                if (!isActive) break
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    /** join 時に presence 登録する（接続済みなら即送信。未接続でも再接続時の resync で回復する）。 */
    fun notifyJoin(uuid: UUID) = sendPresence("join", uuid)

    /** quit 時に presence 解除する。 */
    fun notifyQuit(uuid: UUID) = sendPresence("quit", uuid)

    private fun sendPresence(action: String, uuid: UUID) {
        val active = session ?: return
        val text = VaultSyncProtocol.presence(action, uuid.toString(), serverName)
        scope.launch {
            runCatching { active.send(Frame.Text(text)) }
                .onFailure { plugin.logger.fine("presence($action) 送信に失敗: ${it.message}") }
        }
    }

    private suspend fun connectAndRun() {
        client.webSocket(request = { url(wsUrl()) }) {
            session = this
            service.setConnected(true)
            plugin.logger.info("Vault同期WebSocketに接続しました")

            registerOnlineAndResync(this)

            for (frame in incoming) {
                if (frame is Frame.Text) handleFrame(frame.readText())
            }
        }
    }

    // 在席者全員を presence 再登録し、残高を full resync する（VaultProvider 5.2/5.5）。
    // presence を「先に同期送信」してから resync することで、resync 取得後に届く新しい変更（version 大）が
    // applyAuthoritative で確実に収束する（presence→resync の順序。取りこぼし窓を最小化する）。
    private suspend fun registerOnlineAndResync(socket: WebSocketSession) {
        val online = onlineUuidsOnMain()
        for (uuid in online) {
            // セッション上で presence フレームを直接 await 送信（非同期 launch ではなく確実に先行させる）。
            runCatching { socket.send(Frame.Text(VaultSyncProtocol.presence("join", uuid.toString(), serverName))) }
                .onFailure { plugin.logger.fine("presence(join) 送信に失敗: ${it.message}") }
            service.preload(uuid)
        }
        if (online.isNotEmpty()) {
            plugin.logger.info("Vault: 在席 ${online.size} 名を presence 再登録し残高を再同期しました")
        }
    }

    private fun handleFrame(text: String) {
        val event = VaultSyncProtocol.decode(text) ?: return
        when (event.type?.lowercase()) {
            "balance" -> applyBalanceEvent(event)
            "ping" -> session?.let { s -> scope.launch { runCatching { s.send(Frame.Text(VaultSyncProtocol.pong())) } } }
        }
    }

    private fun applyBalanceEvent(event: VaultSyncProtocol.ServerEvent) {
        val uuidStr = event.uuid ?: return
        val balance = event.balance ?: return
        val version = event.version ?: return
        val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return
        // 未キャッシュ（オフライン）への push は no-op で無視される。version 方式で冪等・順序安全。
        service.cache.applyAuthoritative(uuid, balance, version)
    }

    private suspend fun onlineUuidsOnMain(): List<UUID> {
        if (plugin.server.isPrimaryThread) {
            return plugin.server.onlinePlayers.map { it.uniqueId }
        }
        val deferred = CompletableDeferred<List<UUID>>()
        plugin.server.scheduler.runTask(plugin, Runnable {
            deferred.complete(plugin.server.onlinePlayers.map { it.uniqueId })
        })
        return deferred.await()
    }

    private fun wsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        val wsBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        val encoded = URLEncoder.encode(serverName, Charsets.UTF_8.name())
        return "$wsBase/api/Vault/ws?server=$encoded"
    }
}
