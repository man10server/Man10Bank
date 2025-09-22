package red.man10.man10bank.ui.loan

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI

/**
 * 担保設定画面。
 * - コンストラクタで既存の担保情報を受け取り、GUIクローズ時に更新コールバックを呼ぶ
 * - スロット0〜7が担保領域、8が閉じるボタン
 */
class CollateralSetupUI(
    private val player: Player,
    initialCollaterals: List<ItemStack>?,
    private val onUpdate: (newItems: List<ItemStack>) -> Unit,
    previousUI: InventoryUI? = null,
) : CollateralBaseUI(
    title = "§6§l担保の設定",
    editable = true,
    previousUI = previousUI,
    onClose = object : OnClose() {
        override fun onClose(ui: InventoryUI, event: InventoryCloseEvent) {
            // プレイヤーが閉じたタイミングで担保情報を更新
            val base = ui as CollateralBaseUI
            val newItems = base.getCollateralItems()
            // UI上のアイテムはプレイヤーに返却（消失防止）
            val inv2 = base.getInventory()
            for (slot in CollateralBaseUI.COLLATERAL_SLOTS) {
                val it = inv2.getItem(slot) ?: continue
                if (!it.type.isAir) {
                    event.player.inventory.addItem(it)
                    inv2.setItem(slot, null)
                }
            }
            onUpdate(newItems)
        }
    }
) {
    init {
        // 既存担保を先頭から配置
        initialCollaterals?.let { setCollateralItems(it.map { it.clone() }) }
    }

    fun open() { super.open(player) }
}
