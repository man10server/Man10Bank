package red.man10.man10bank.ui.loan

import red.man10.man10bank.ui.UIButton
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 担保の確認画面（参照専用）。
 * - スロット0〜8に担保を表示（クリックしても何も起きない）
 */
class CollateralViewUI(
    private val player: Player,
    collaterals: List<ItemStack>?,
) : CollateralBaseUI(
    title = "§6§l担保の確認",
    editable = false,
) {
    init {
        collaterals?.let { setCollateralItems(it) }
    }

    fun open() { super.open(player) }
}
