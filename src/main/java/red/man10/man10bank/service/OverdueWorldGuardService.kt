package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.ServerLoanApiClient
import red.man10.man10bank.config.OverdueWorldBlockConfig
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.runOnMainThread
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * サーバーローンの延滞者を、設定されたワールドに入れないようにする。
 * - テレポートやポータルでの進入は、直近に照会した延滞状態をもとにその場でキャンセルする。
 * - ログイン（サーバー移動を含む）やキャンセルできないワールド移動で対象ワールドに入った場合は、
 *   延滞状態を照会し直し、延滞中ならキックする。
 * - 延滞状態を照会できないときは制限しない（銀行サーバー停止中に全員を締め出さないため）。
 */
class OverdueWorldGuardService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val api: ServerLoanApiClient,
    initialConfig: OverdueWorldBlockConfig,
) : Listener {

    /** 現在の設定。/bankop overdueworld で差し替わる。 */
    @Volatile
    private var config: OverdueWorldBlockConfig = initialConfig

    companion object {
        /** 進入を拒否したときの通知と再照会の最短間隔（ミリ秒）。ポータル内で連続して発火しても連投しない。 */
        private const val DENY_NOTICE_INTERVAL_MS = 3_000L
    }

    /** 直近に照会した延滞状態（オンラインのプレイヤーのみ保持）。 */
    private val overdue = ConcurrentHashMap<UUID, Boolean>()

    /** 進入拒否を最後に通知した時刻。 */
    private val lastDeniedNotice = ConcurrentHashMap<UUID, Long>()

    /** 起動／リロード時点でオンラインのプレイヤーの延滞状態を照会する。 */
    fun start() {
        if (!config.enabled) return
        plugin.logger.info("延滞者の進入禁止ワールド: ${blockedWorlds().joinToString(", ")}")
        plugin.server.onlinePlayers.forEach { refresh(it) }
    }

    /** 延滞者が入れないワールド名の一覧（小文字・名前順）。 */
    fun blockedWorlds(): List<String> = config.worlds.sorted()

    /**
     * ワールドを対象に追加して config.yml に保存する。既に対象なら false。
     * オンラインのプレイヤーの延滞状態を照会し直すため、対象ワールドにいる延滞者はその場でキックされる。
     */
    fun addWorld(worldName: String): Boolean {
        if (config.isBlocked(worldName)) return false
        config = config.withWorld(worldName)
        save()
        plugin.server.onlinePlayers.forEach { refresh(it) }
        return true
    }

    /** ワールドを対象から外して config.yml に保存する。対象でなければ false。 */
    fun removeWorld(worldName: String): Boolean {
        if (!config.isBlocked(worldName)) return false
        config = config.withoutWorld(worldName)
        save()
        return true
    }

    private fun save() {
        plugin.config.set("overdueWorldBlock.worlds", blockedWorlds())
        plugin.saveConfig()
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        if (!config.enabled) return
        refresh(e.player)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        overdue.remove(e.player.uniqueId)
        lastDeniedNotice.remove(e.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) = denyIfOverdue(e)

    // PlayerPortalEvent は PlayerTeleportEvent とは別のハンドラリストを持つため個別に受ける。
    @EventHandler(ignoreCancelled = true)
    fun onPortal(e: PlayerPortalEvent) = denyIfOverdue(e)

    @EventHandler
    fun onChangedWorld(e: PlayerChangedWorldEvent) {
        if (!config.enabled) return
        if (config.isBlocked(e.player.world.name)) refresh(e.player)
    }

    private fun denyIfOverdue(e: PlayerTeleportEvent) {
        if (!config.enabled) return
        val to = e.to.world ?: return
        if (to == e.from.world || !config.isBlocked(to.name)) return

        val player = e.player
        if (overdue[player.uniqueId] != true) return
        e.isCancelled = true

        val now = System.currentTimeMillis()
        val last = lastDeniedNotice[player.uniqueId]
        if (last != null && now - last < DENY_NOTICE_INTERVAL_MS) return
        lastDeniedNotice[player.uniqueId] = now

        Messages.error(player, config.message)
        // 延滞が解消済みなら次回から入れるよう、状態を取り直す。
        refresh(player)
    }

    /** 延滞状態を照会し直し、延滞中で対象ワールドにいる場合はキックする。 */
    private fun refresh(player: Player) {
        val uuid = player.uniqueId
        scope.launch {
            val result = api.isOverdue(uuid)
            val isOverdue = result.getOrNull()
            if (isOverdue == null) {
                plugin.logger.warning("延滞状態の照会に失敗しました（進入は制限しません）: uuid=$uuid 理由=${result.errorMessage()}")
                return@launch
            }
            plugin.runOnMainThread {
                val online = plugin.server.getPlayer(uuid) ?: return@runOnMainThread
                overdue[uuid] = isOverdue
                if (isOverdue && config.isBlocked(online.world.name)) {
                    online.kick(LegacyComponentSerializer.legacySection().deserialize(config.message))
                }
            }
        }
    }
}
