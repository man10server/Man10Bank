package red.man10.man10bank.ui.atm

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.service.CashExchangeService
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.VaultManager
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
    private val vaultManager: VaultManager,
) : InventoryUI(
    title = "電子マネーを現金にする",
    size = 27,
    previousUI = null,
) {

    private val slots = listOf(10, 11, 12, 14, 15, 16)
    private val amounts = listOf(10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0)

    init {
        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        fillBackground(filler)
    }

    fun open() {
        createButton()
        super.open(player)
    }

    private fun createButton() {
        val balance = vaultManager.getBalance(player)

        // 金種ボタンを配置
        for (i in slots.indices) {
            val slot = slots[i]
            val amount = amounts[i]
            setButton(slot, createWithdrawButton(amount, balance))
        }
    }

    private fun createWithdrawButton(amount: Double, balance: Double): UIButton {
        val base = cashItems.getItemForAmount(amount)?: ItemStack(Material.PAPER)
        val lore = (base.lore()?: listOf()).toMutableList()
        lore.add(Component.text("§b§l電子マネー: ${BalanceFormats.colored(balance)}§b§l円"))
        base.lore(lore)

        val icon = base.clone()
        return UIButton(icon).onClick { p, _ ->
            val item = exchange.withdrawVaultToCash(player, amount)
            if (item == null) {
                Messages.warn(p, "残高不足、または引き出しできませんでした。")
                return@onClick
            }
            val inv = p.inventory
            inv.addItem(item)
            Messages.send(p, "引き出しました: ${BalanceFormats.colored(amount)}")
            open()
        }
    }
}

