package red.man10.man10bank.ui.loan

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI

/**
 * 債務者用の担保返還受け取りメニュー。
 * - 債権者から解放された担保を債務者が受け取るUI
 * - スロット0〜7に担保を配置する。サーバー側では開く前に返還済みになっているため、
 *   取り出さずに閉じた分はクローズ時にインベントリへ戻す（戻さないと担保が消える）
 */
class CollateralDebtorReleaseUI(
    private val player: Player,
    collaterals: List<ItemStack>?,
    private val onReleased: () -> Unit,
    previousUI: InventoryUI? = null,
) : CollateralBaseUI(
    title = "§6§l担保の返還受け取り",
    editable = true,
    previousUI = previousUI,
    onClose = object : OnClose() {
        override fun onClose(ui: InventoryUI, event: InventoryCloseEvent) {
            (ui as CollateralBaseUI).returnRemainingCollateral(player)
            onReleased()
        }
    }
) {
    init {
        collaterals?.let { setCollateralItems(it.map { it.clone() }) }
    }

    fun open() { super.open(player) }
}
