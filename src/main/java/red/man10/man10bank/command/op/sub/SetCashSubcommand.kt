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
        // ATMは整数の金額キーで金種を引くため、1円以上の整数のみ受け付ける（"0100" などは "100" に正規化）
        val amount = args[1].toLongOrNull()?.takeIf { it > 0 }?.toString()
        if (amount == null) {
            Messages.error(sender, "金額は1以上の整数（円）で指定してください。")
            return true
        }

        val hand = sender.inventory.itemInMainHand
        if (hand.type == Material.AIR || hand.amount <= 0) {
            Messages.error(sender, "手に持っているアイテムが無効です。登録するアイテムを手に持ってください。")
            return true
        }

        val saveCopy = hand.clone().asOne()
        cashItemManager.save(saveCopy, amount)
        Messages.send(sender, "現金アイテムを登録しました。1個あたりの金額: ${amount}。アイテム: ${saveCopy.type}。")
        return true
    }
}

