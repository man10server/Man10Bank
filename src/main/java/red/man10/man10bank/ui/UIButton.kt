package red.man10.man10bank.ui

import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/**
 * GUI上のボタン表現。
 * - アイコン（ItemStack）
 * - クリック時のアクション
 * - デフォルトのキャンセル可否
 */
class UIButton(
    var icon: ItemStack,
    var cancelOnClick: Boolean = true,
    private var onClickAction: ((player: Player, event: InventoryClickEvent) -> Unit)? = null,
) {
    fun onClick(action: (player: Player, event: InventoryClickEvent) -> Unit): UIButton {
        this.onClickAction = action
        return this
    }

    internal fun trigger(player: Player, event: InventoryClickEvent) {
        onClickAction?.invoke(player, event)
    }
}

