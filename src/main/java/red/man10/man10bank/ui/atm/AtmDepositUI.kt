package red.man10.man10bank.ui.atm

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.ui.InventoryUI
import red.man10.man10bank.ui.UIButton
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

/**
 * 現金を電子マネーへ入金するUI。
 * - 上45スロットが現金預け入れ領域
 * - 下段(45-53)は操作領域（水色ガラス + 入金ボタン）
 * - 現金アイテムのみプレイヤーインベントリとのスワップを許可
 */
class AtmDepositUI(
    private val player: Player,
    private val cashItems: CashItemManager,
    private val exchange: CashExchangeService,
    previousUI: InventoryUI? = null,
) : InventoryUI(
    title = "現金を電子マネーにする",
    size = 54,
    previousUI = previousUI,
    onGuiClick = object : OnGuiClick() {
        override fun onGuiClick(ui: InventoryUI, event: InventoryClickEvent, button: UIButton?) {
            if (event.action == InventoryAction.HOTBAR_SWAP) {
                event.isCancelled = true
                return
            }
            val current = event.currentItem?: return
            if (cashItems.getAmountForItem(current) == null) {
                event.isCancelled = true
            }
        }
    },
    onPlayerClick = object : OnPlayerClick() {
        override fun onPlayerClick(ui: InventoryUI, event: InventoryClickEvent) {
            if (event.action == InventoryAction.HOTBAR_SWAP) {
                event.isCancelled = true
                return
            }
            val current = event.currentItem?: return
            if (cashItems.getAmountForItem(current) == null) {
                event.isCancelled = true
            }
        }
    }
) {

    init {
        for (slot in 45 until 54) {
            setButton(slot, createDepositButton())
        }
    }

    fun open(){
        super.open(player)
    }

    private fun createDepositButton(): UIButton {
        val icon = ItemStack(Material.LIGHT_BLUE_STAINED_GLASS).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§b§l入金して閉じる"))
            }
        }
        return UIButton(icon).onClick { p, _ ->
            // 預け入れ領域(0..44)のアイテムを対象に入金
            val top = this.getInventory()
            val targets = (0 until 45)
                .mapNotNull { top.getItem(it) }
                .toTypedArray()

            val deposited = exchange.depositCashToVault(player, targets)
            if (deposited > 0.0) {
                Messages.send(p, "入金しました: ${BalanceFormats.coloredYen(deposited)}")
            }
            p.closeInventory()
        }
    }
}
