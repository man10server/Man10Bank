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
}
