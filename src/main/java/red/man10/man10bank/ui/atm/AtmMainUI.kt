package red.man10.man10bank.ui.atm

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.ui.InventoryUI
import red.man10.man10bank.ui.UIButton
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages

class AtmMainUI(
    private val player: Player,
    vaultManager: VaultManager,
) : InventoryUI(
    title = "§6§lATM",
    size = 27,
) {

    private val balance: Double = vaultManager.getBalance(player)

    init {
        fillBackground(createFiller())
        arrayOf(10, 11, 12).forEach { slot -> setButton(slot, createDepositButton()) }
        arrayOf(14, 15, 16).forEach { slot -> setButton(slot, createWithdrawButton()) }
    }

    private fun createFiller(): ItemStack {
        return ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { meta -> meta.displayName(Component.text("")) }
        }
    }

    fun open(){
        super.open(player)
    }

    private fun createDepositButton(): UIButton {
        val item = ItemStack(Material.CHEST).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§b§l入金する"))
                meta.lore(listOf(Component.text("§b§l現在の電子マネー: ${BalanceFormats.colored(balance)}")))
            }
        }
        val button = UIButton(item)
        button.onClick { player, _ ->
            Messages.send(player,"§c§l入金機能は現在メンテナンス中です。しばらくお待ちください。")
        }
        return button
    }

    private fun createWithdrawButton(): UIButton {
        val item = ItemStack(Material.DISPENSER).apply {
            editMeta { meta ->
                meta.displayName(Component.text("§c§l出金する"))
                meta.lore(listOf(Component.text("§b§l現在の電子マネー: ${BalanceFormats.colored(balance)}")))
            }
        }
        val button = UIButton(item)
        button.onClick { player, _ ->
            Messages.send(player,"§c§l出金機能は現在メンテナンス中です。しばらくお待ちください。")
        }
        return button
    }
}