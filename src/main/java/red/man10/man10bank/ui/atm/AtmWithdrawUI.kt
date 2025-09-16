package red.man10.man10bank.ui.atm

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.ui.InventoryUI
import red.man10.man10bank.ui.UIButton
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * 電子マネーを現金に引き出すUI。
 * - 背景に灰色ガラス
 * - 10,11,12,14,15,16 に金種ボタン
 */
class AtmWithdrawUI(
    private val player: Player,
    private val cashItems: CashItemManager,
    private val exchange: CashExchangeService,
    previousUI: InventoryUI? = null,
) : InventoryUI(
    title = "電子マネーを現金にする",
    size = 27,
    previousUI = previousUI,
    onGuiClick = object : OnGuiClick() {
        override fun onGuiClick(ui: InventoryUI, event: InventoryClickEvent, button: UIButton?) {
            // 背景クリックはキャンセルされるが、特別な処理は不要
        }
    }
) {

    private val slots = listOf(10, 11, 12, 14, 15, 16)
    private val amounts = listOf(10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0)

    init {
        // 背景（灰色ガラス）
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        fillBackground(filler)

        // 金種ボタンを配置
        for (i in slots.indices) {
            val slot = slots[i]
            val amount = amounts[i]
            setButton(slot, createWithdrawButton(amount))
        }
    }

    private fun createWithdrawButton(amount: Double): UIButton {
        val base = cashItems.getItemForAmount(amount) ?: ItemStack(Material.PAPER).apply {
            editMeta { it.displayName(Component.text("${BalanceFormats.colored(amount)}")) }
        }
        val icon = base.clone()
        return UIButton(icon).onClick { p, _ ->
            val items = exchange.withdrawVaultToCash(player, amount)
            if (items.isEmpty()) {
                Messages.warn(p, "残高不足、または引き出しできませんでした。")
                return@onClick
            }
            val inv = p.inventory
            val leftovers = mutableMapOf<Int, ItemStack>()
            items.forEach { stack ->
                val remains = inv.addItem(stack)
                if (remains.isNotEmpty()) leftovers.putAll(remains)
            }
            // 余りは足元にドロップ
            if (leftovers.isNotEmpty()) {
                leftovers.values.forEach { p.world.dropItemNaturally(p.location, it) }
            }
            Messages.send(p, "引き出しました: ${BalanceFormats.colored(amount)}")
        }
    }
}

