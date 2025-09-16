package red.man10.man10bank.ui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import red.man10.man10bank.Man10Bank

/**
 * カスタムインベントリ（InventoryHolder）。
 * - スロットIDにUIButtonを配置
 * - クリックイベントは UIService から委譲される
 */
class InventoryUI(
    private val plugin: Man10Bank,
    private val title: String,
    size: Int,
) : InventoryHolder {

    private val invSize: Int = normalizeSize(size)
    private val inventory: Inventory = Bukkit.createInventory(this, invSize, title)
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
     * エイリアス: setButton
     */
    fun addButton(slot: Int, button: UIButton): InventoryUI = setButton(slot, button)

    /**
     * プレイヤーにGUIを開く。
     */
    fun open(player: Player) {
        player.openInventory(inventory)
    }

    /**
     * UIService側からのクリック委譲。
     * 戻り値: true = 処理した/キャンセル、false = 未処理
     */
    internal fun handleClick(event: InventoryClickEvent): Boolean {
        val who = event.whoClicked
        val top = event.view.topInventory
        if (event.clickedInventory == null || top.holder !== this) return false

        // トップインベントリ領域のみ扱う
        val raw = event.rawSlot
        if (raw !in 0 until invSize) return false

        val button = buttons[raw] ?: return false
        // デフォルト挙動: キャンセル
        if (button.cancelOnClick) event.isCancelled = true

        if (who is Player) {
            button.trigger(who, event)
        }
        return true
    }

    private fun normalizeSize(input: Int): Int {
        // 9の倍数かつ1〜6行(9〜54)に丸め
        val clamped = input.coerceIn(9, 54)
        val rows = (clamped + 8) / 9
        val normalized = rows * 9
        return normalized.coerceAtMost(54)
    }
}

