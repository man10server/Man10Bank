package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.PlayersApiClient
import red.man10.man10bank.api.model.request.PlayerRegisterRequest
import red.man10.man10bank.util.errorMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ログイン時に UUID と Minecraft ID を銀行サーバーの名簿（player_profile）へ登録する。
 * - サーバー側の名前解決はこの名簿を最優先に使う（改名はここで追従する）。認証サーバー(Mojang)には依存しない。
 * - 登録に失敗した場合は保留にし、オンラインの間は定期的に再試行する（銀行サーバー一時停止中のログインを取りこぼさない）。
 * - ログイン直後の他処理（資産スナップショット等）はサーバー側で名前解決が必要なため、
 *   [awaitRegistration] で登録完了を待ってから送れるようにする。
 */
class PlayerRegistryService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: PlayersApiClient,
) : Listener {

    companion object {
        /** 保留分の再試行間隔（tick）。 */
        private const val RETRY_INTERVAL_TICKS = 20L * 60
        /** 登録完了待ちの上限（ミリ秒）。超えた場合は待たずに後続処理へ進む。 */
        private const val AWAIT_TIMEOUT_MS = 5_000L
    }

    /** 登録に失敗して保留中のプレイヤー（uuid -> name）。 */
    private val pending = ConcurrentHashMap<UUID, String>()

    /** 進行中の登録（uuid -> 完了待ち）。 */
    private val inFlight = ConcurrentHashMap<UUID, Deferred<Boolean>>()

    /** 起動／リロード時点でオンラインのプレイヤーを登録し、保留分の再試行タスクを開始する。 */
    fun start() {
        plugin.server.onlinePlayers.forEach { register(it.uniqueId, it.name) }
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { retryPending() }, RETRY_INTERVAL_TICKS, RETRY_INTERVAL_TICKS)
    }

    // 他のログイン処理（資産スナップショット等）が名前解決できるよう、最優先で登録を開始する。
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(e: PlayerJoinEvent) {
        register(e.player.uniqueId, e.player.name)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        pending.remove(e.player.uniqueId)
    }

    /** ログイン直後の登録が進行中なら完了を待つ（最大 [AWAIT_TIMEOUT_MS]）。登録後に名前解決が必要な処理の前に呼ぶ。 */
    suspend fun awaitRegistration(uuid: UUID) {
        val deferred = inFlight[uuid] ?: return
        withTimeoutOrNull(AWAIT_TIMEOUT_MS) { deferred.await() }
    }

    private fun register(uuid: UUID, name: String): Deferred<Boolean> {
        val deferred = scope.async { registerOnce(uuid, name) }
        inFlight[uuid] = deferred
        deferred.invokeOnCompletion { inFlight.remove(uuid, deferred) }
        return deferred
    }

    /** 1回だけ登録を試みる。成功なら true。失敗時は保留に入れて false。 */
    private suspend fun registerOnce(uuid: UUID, name: String): Boolean {
        val result = api.register(PlayerRegisterRequest(uuid.toString(), name))
        if (result.isSuccess) {
            pending.remove(uuid)
            return true
        }
        pending[uuid] = name
        plugin.logger.warning(
            "プレイヤー名簿への登録に失敗しました（オンラインの間は定期的に再試行します）: uuid=$uuid name=$name 理由=${result.errorMessage()}"
        )
        return false
    }

    /** 保留分のうちオンラインのプレイヤーだけ再登録する（メインスレッドのタイマーから呼ばれる）。 */
    private fun retryPending() {
        if (pending.isEmpty()) return
        for ((uuid, _) in pending.entries.toList()) {
            val online = plugin.server.getPlayer(uuid)
            if (online == null) {
                pending.remove(uuid)
                continue
            }
            register(uuid, online.name)
        }
    }
}
