package red.man10.man10bank.ui.loan

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI

/**
 * 担保設定画面。
 * - スロット0〜7が担保領域、8が閉じるボタン
 * - 置かれたアイテムは担保の「登録」に使うだけで預からない。クローズ時に登録内容をコールバックで渡し、
 *   実物はプレイヤーのインベントリへ戻す（提案はメモリ上にしかなく、預かると再起動等で消えるため）。
 * - [initialCollaterals] には呼び出し側がインベントリから取り出した実物を渡すこと（戻す際に増殖しないように）。
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
            val base = ui as CollateralBaseUI
            val newItems = base.getCollateralItems()
            base.returnCollateralItemsTo(player)
            onUpdate(newItems)
        }
    }
) {
    init {
        initialCollaterals?.let { setCollateralItems(it) }
    }

    /** 開けなかった場合は、並べた担保をインベントリへ戻して false を返す。 */
    fun open(): Boolean = openOrReturn(player)
}
