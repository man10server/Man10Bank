package red.man10.man10bank.ui

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import red.man10.man10bank.Man10Bank

/**
 * GUIイベントを横取りして InventoryUI にディスパッチするリスナー。
 */
class UIService(
    private val plugin: Man10Bank,
) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder
        if (holder is InventoryUI) {
            holder.handleClick(event)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val holder = event.view.topInventory.holder
        if (holder is InventoryUI) {
            holder.handleClose(event)
        }
    }

    /**
     * プラグイン停止時(onDisable)に、開いているGUIのクローズ処理を実行してから閉じる。
     * - 停止中はプラグインが無効扱いのためイベントリスナーが呼ばれず、そのまま閉じるとクローズ時の後処理
     *   （担保の返却・ATMの入金など）が走らずにGUI内のアイテムが消える。
     * - サーバー停止時はプラグインの停止がプレイヤーの保存より先に行われるため、ここで戻したアイテムは保存される。
     */
    fun closeAllForShutdown() {
        for (player in plugin.server.onlinePlayers) {
            val view = player.openInventory
            val ui = view.topInventory.holder as? InventoryUI ?: continue
            runCatching { ui.handleClose(InventoryCloseEvent(view, InventoryCloseEvent.Reason.PLUGIN)) }
                .onFailure { plugin.logger.severe("停止時のGUIクローズ処理に失敗しました: player=${player.name} ${it.message}") }
            player.closeInventory(InventoryCloseEvent.Reason.PLUGIN)
        }
    }
}
