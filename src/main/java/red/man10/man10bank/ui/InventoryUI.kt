package red.man10.man10bank.ui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * カスタムインベントリ（InventoryHolder）。
 * - スロットIDにUIButtonを配置
 * - クリック/クローズのフック（OnClick/OnClose）をコンストラクタで受け付け
 * - クリックイベントは UIService から委譲される
 */
open class InventoryUI(
    title: String,
    size: Int,
    private val onClick: OnClick? = null,
    private val onClose: OnClose? = null,
    private val onGuiClick: OnGuiClick? = null,
    private val onPlayerClick: OnPlayerClick? = null,
    private val previousUI: InventoryUI? = null,
) : InventoryHolder {

    /** クリック時フック（拡張用・全域） */
    abstract class OnClick { abstract fun onButtonClick(ui: InventoryUI, event: InventoryClickEvent) }
    /** クローズ時フック（拡張用） */
    abstract class OnClose { abstract fun onClose(ui: InventoryUI, event: InventoryCloseEvent) }
    /** GUIインベントリ領域クリックのフック */
    abstract class OnGuiClick { abstract fun onGuiClick(ui: InventoryUI, event: InventoryClickEvent, button: UIButton?) }
    /** プレイヤーインベントリ領域クリックのフック */
    abstract class OnPlayerClick { abstract fun onPlayerClick(ui: InventoryUI, event: InventoryClickEvent) }

    private val invSize: Int = normalizeSize(size)
    private val inventory: Inventory = Bukkit.createInventory(this, invSize, Component.text(title))
    private val buttons: MutableMap<Int, UIButton> = mutableMapOf()

    override fun getInventory(): Inventory = inventory

    /**
     * 指定スロットにボタンを設置。
     */
    fun setButton(slot: Int, button: UIButton): InventoryUI {
        require(slot in 0 until invSize) { "スロット範囲外です: $slot" }
        buttons[slot] = button
        inventory.setItem(slot, button.icon)
        return this
    }

    /**
     * インベントリの空きスロットに順番にボタンを追加。
     * Inventory#addItem と同様、配置できなかったボタン（キー: 引数インデックス）が返る。
     */
    fun addButton(button: UIButton): InventoryUI = setButton(inventory.firstEmpty(), button)

    /**
     * インベントリ全体を指定アイテムで埋める。
     */
    fun fillBackground(item: ItemStack): InventoryUI {
        for (slot in 0 until invSize) {
            setButton(slot, UIButton(item.clone()))
        }
        return this
    }

    /**
     * プレイヤーにGUIを開く。
     */
    fun open(player: Player) { player.openInventory(inventory) }

    /**
     * UIService側からのクリック委譲。
     * 戻り値: true = 処理した/キャンセル、false = 未処理
     */
    internal fun handleClick(event: InventoryClickEvent): Boolean {
        val top = event.view.topInventory
        if (top.holder !== this) return false

        val raw = event.rawSlot
        val clicked = event.clickedInventory

        val inTop = raw in 0 until invSize && clicked != null && clicked === top
        val inPlayer = clicked != null && clicked === event.view.bottomInventory

        if (inTop) {
            val button = buttons[raw]
            if (button != null) {
                event.isCancelled = true
                val who = event.whoClicked
                if (who is Player) button.trigger(who, event)
            }
            onGuiClick?.onGuiClick(this, event, button)
        } else if (inPlayer) {
            onPlayerClick?.onPlayerClick(this, event)
        }

        // 全域フック（最後に）
        onClick?.onButtonClick(this, event)
        return true
    }

    /**
     * UIService側からのクローズ委譲。
     */
    internal fun handleClose(event: InventoryCloseEvent) {
        onClose?.onClose(this, event)
        if (event.reason == InventoryCloseEvent.Reason.PLAYER) {
            val player = event.player as? Player ?: return
            previousUI?.open(player)
        }
    }

    private fun normalizeSize(input: Int): Int {
        // 9の倍数かつ1〜6行(9〜54)に丸め
        val clamped = input.coerceIn(9, 54)
        val rows = (clamped + 8) / 9
        val normalized = rows * 9
        return normalized.coerceAtMost(54)
    }
}
