package red.man10.man10bank.listener

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.service.vault.VaultSyncClient

/**
 * 電子マネーキャッシュの常駐ライフサイクル（VaultProvider 4.5/8.1）。
 * - join: presence 登録 → 電子マネーをプリロード（権威残高をキャッシュへ）。
 * - quit: presence 解除 → キャッシュ退避。
 *
 * presence を先に送ることで、resync 取得後に届く新しい変更（version 大）が
 * [VaultCache.applyAuthoritative] で確実に収束する。
 */
class VaultLifecycleListener(
    private val scope: CoroutineScope,
    private val service: VaultService,
    private val sync: VaultSyncClient,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        sync.notifyJoin(uuid)
        scope.launch { service.preload(uuid) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        sync.notifyQuit(uuid)
        service.evict(uuid)
    }
}
