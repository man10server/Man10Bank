package red.man10.man10bank.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import red.man10.man10bank.service.vault.VaultService

/**
 * join/quit で Provider キャッシュのロード・WARMING_UP・キュー処理・破棄を駆動する(設計書 §10)。
 * - join: VaultService.onJoin(LOADING -> 権威残高ロード -> WARMING_UP -> クールタイム後 READY)。
 * - quit: VaultService.onQuit(DRAINING -> 送信待ちキュー flush -> キャッシュ破棄)。
 */
class VaultLifecycleListener(
    private val service: VaultService,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        service.onJoin(e.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) {
        service.onQuit(e.player.uniqueId)
    }
}
