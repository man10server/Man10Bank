package red.man10.man10bank.ui.loan

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.ui.InventoryUI
import red.man10.man10bank.ui.UIButton

/**
 * 担保関連UIの共通ベース。
 * - スロット0〜8: 担保アイテム領域（editable=true なら編集可）
 * - スロット9: 閉じるボタン
 * - インベントリサイズ: 18（2行）
 * - fillerは使用しない
 */
open class CollateralBaseUI(
    title: String,
    private val editable: Boolean,
    previousUI: InventoryUI? = null,
    onClose: OnClose? = null,
) : InventoryUI(
    title = title,
    size = 9,
    previousUI = previousUI,
    onClose = onClose,
    onGuiClick = object : OnGuiClick() {
        override fun onGuiClick(ui: InventoryUI, event: InventoryClickEvent, button: UIButton?) {
            // ホットバー切替は禁止
            if (event.action == InventoryAction.HOTBAR_SWAP) {
                event.isCancelled = true
                return
            }
            val raw = event.rawSlot
            val inCollateralArea = raw in COLLATERAL_SLOTS
            val isCloseButton = raw == CLOSE_SLOT
            if (isCloseButton) {
                // ボタンハンドラに任せるため、ここでは特に制御しない
                return
            }
            if (inCollateralArea) {
                if (!editable) {
                    event.isCancelled = true
                }
                return
            }
            // それ以外はGUI側クリック不可
            event.isCancelled = true
        }
    },
    onPlayerClick = object : OnPlayerClick() {
        override fun onPlayerClick(ui: InventoryUI, event: InventoryClickEvent) {
            // プレイヤーインベントリ側のホットバー切替は禁止
            if (event.action == InventoryAction.HOTBAR_SWAP) {
                event.isCancelled = true
            }
        }
    }
) {

    companion object {
        /** 担保領域スロット */
        val COLLATERAL_SLOTS: IntRange = 0..7
        /** 閉じるボタンのスロット */
        const val CLOSE_SLOT = 8
    }

    init {
        // 閉じるボタンのみ配置
        val icon = ItemStack(Material.BARRIER).apply {
            editMeta { it.displayName(Component.text("§c§l閉じる")) }
        }
        setButton(CLOSE_SLOT, UIButton(icon).onClick { p, e ->
            e.isCancelled = true
            p.closeInventory()
        })
    }

    /** 現在の担保アイテム一覧（非null/非Airのみ、cloneで返却） */
    fun getCollateralItems(): List<ItemStack> = COLLATERAL_SLOTS
        .mapNotNull { getInventory().getItem(it) }
        .filter { !it.type.isAir }
        .map { it.clone() }

    /** 担保スロットを一旦クリアし、先頭から順に設定する */
    fun setCollateralItems(items: List<ItemStack?>) {
        val inv = getInventory()
        // まずクリア
        for (slot in COLLATERAL_SLOTS) inv.setItem(slot, null)
        // 先頭から詰めて配置
        var idx = 0
        for (item in items) {
            if (idx > COLLATERAL_SLOTS.last) break
            inv.setItem(COLLATERAL_SLOTS.first + idx, item)
            idx++
        }
    }

    /** List<ItemStack> 用のヘルパー */
    fun setCollateralItems(items: List<ItemStack>) {
        setCollateralItems(items.map { it })
    }

    /** 担保エリアが全て空か */
    fun isCollateralAreaEmpty(): Boolean = COLLATERAL_SLOTS.all {
        val i = getInventory().getItem(it)
        i == null || i.type.isAir
    }
}
