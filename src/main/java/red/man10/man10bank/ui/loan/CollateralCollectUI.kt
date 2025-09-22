package red.man10.man10bank.ui.loan

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI

/**
 * 担保の受け取り画面。
 * - 債権者が担保を回収するためのUI
 * - スロット0〜8に担保を配置し、取り出して全て空ならクローズ時にコールバック
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
            // クローズ時にスロットが空なら受け取り完了とみなす
            val empty = (ui as CollateralBaseUI).isCollateralAreaEmpty()
            if (empty) {
                onCollected()
            }
        }
    }
) {
    init {
        collaterals?.let { setCollateralItems(it.map { it.clone() }) }
    }

    fun open() { super.open(player) }
}
