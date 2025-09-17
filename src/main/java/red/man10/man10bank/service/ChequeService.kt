package red.man10.man10bank.service

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.util.Messages

/**
 * 小切手(cheque)関連のイベントハンドラ。
 * - 仕様は今後拡張。現状は土台のみ。
 */
class ChequeService(private val plugin: JavaPlugin) : Listener {

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // TODO: 小切手アイテムの右クリック使用などの処理をここに実装
        // 現状は土台のみで処理は行わない
    }
}

