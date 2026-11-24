package red.man10.man10bank.ui.loan

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI

/**
 * 担保の受け取り画面。
 * - 債権者が担保を回収するためのUI
 * - スロット0〜7に担保を配置する。サーバー側では開く前に回収済みになっているため、
 *   取り出さずに閉じた分はクローズ時にインベントリへ戻す（戻さないと担保が消える）
 */
class CollateralCollectUI(
    private val player: Player,
    collaterals: List<ItemStack>?,
    private val onCollected: () -> Unit,
    previousUI: InventoryUI? = null,
) : CollateralBaseUI(
    title = "§6§l担保の受け取り",
    editable = true,
    previousUI = previousUI,
    onClose = object : OnClose() {
        override fun onClose(ui: InventoryUI, event: InventoryCloseEvent) {
            (ui as CollateralBaseUI).returnRemainingCollateral(player)
            onCollected()
        }
    }
) {
    init {
        collaterals?.let { setCollateralItems(it.map { it.clone() }) }
    }
}
