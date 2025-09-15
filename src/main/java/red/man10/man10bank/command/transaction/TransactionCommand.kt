package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.util.Messages

/**
 * 入出金コマンドの共通基底クラス。
 * - 権限/プレイヤー/引数チェックを共通化
 * - 金額解決/トランザクション処理はサブクラスに委譲
 */
abstract class TransactionCommand(
    protected val plugin: Man10Bank,
    protected val scope: CoroutineScope,
    protected val vault: VaultManager,
    protected val bank: BankApiClient,
) : CommandExecutor {

    /** 使用方法（例: "/deposit <金額/all>"） */
    protected abstract val usage: String

    /** 金額の解決（all対応含む）。不正な場合は null を返す。 */
    protected abstract suspend fun resolveAmount(player: Player, arg: String): Double?

    /** トランザクションの実処理。ここで入出金・返金などを行う。 */
    protected abstract suspend fun process(player: Player, amount: Double)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("man10bank.user")) {
            Messages.error(sender, "このコマンドを実行する権限がありません。")
            return true
        }
        if (sender !is Player) {
            Messages.error(sender, "このコマンドはプレイヤーのみ使用できます。")
            return true
        }
        if (args.size != 1) {
            Messages.warn(sender, "使い方: $usage")
            return true
        }

        val arg = args[0]
        scope.launch {
            val amount = resolveAmount(sender, arg)
            if (amount == null || amount <= 0.0) {
                Messages.error(plugin, sender, "金額が不正です。正の数または all を指定してください。")
                return@launch
            }
            process(sender, amount)
        }
        return true
    }
}
