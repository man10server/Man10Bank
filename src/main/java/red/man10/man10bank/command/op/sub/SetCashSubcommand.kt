package red.man10.man10bank.command.op.sub

import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.util.Messages

/**
 * /bankop setcash <金額>
 */
class SetCashSubcommand(
    private val cashItemManager: CashItemManager,
) : BankOpSubcommand {
    override val name: String = "setcash"
    override val usage: String = "/bankop setcash <金額> - 手持ちアイテムを現金として登録"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (sender !is Player) {
            Messages.error(sender, "プレイヤーのみ実行できます。ゲーム内で実行してください。")
            return true
        }
        if (args.size < 2) {
            Messages.error(sender, "金額を指定してください。例: /bankop setcash <金額> - 手持ちアイテムを現金として登録")
            return true
        }
        val amount = args[1]

        val hand = sender.inventory.itemInMainHand
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Messages.error(sender, "手に持っているアイテムが無効です。登録するアイテムを手に持ってください。")
            return true
        }

        val saveCopy = hand.clone().asOne()
        cashItemManager.save(saveCopy, args[1])
        Messages.send(sender, "現金アイテムを登録しました。1個あたりの金額: ${amount}。アイテム: ${saveCopy.type}。")
        return true
    }
}

