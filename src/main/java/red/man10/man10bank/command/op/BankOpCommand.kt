package red.man10.man10bank.command.op

import kotlinx.coroutines.CoroutineScope
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.command.op.sub.HealthSubcommand
import red.man10.man10bank.command.op.sub.HistorySubcommand
import red.man10.man10bank.command.op.sub.SetCashSubcommand
import red.man10.man10bank.command.op.sub.EnableFeatureSubcommand
import red.man10.man10bank.command.op.sub.DisableFeatureSubcommand
import red.man10.man10bank.service.CashItemManager
import red.man10.man10bank.service.HealthService
import red.man10.man10bank.service.FeatureToggleService
import red.man10.man10bank.util.Messages

/**
 * /bankop コマンド（OP専用）: サブコマンドにディスパッチ
 */
class BankOpCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val healthService: HealthService,
    cashItemManager: CashItemManager,
    estateService: red.man10.man10bank.service.EstateService,
    private val featureToggles: FeatureToggleService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = true,
    allowGeneralUser = false,
) {

    private val subcommands: Map<String, BankOpSubcommand> = listOf(
        // ヘルスチェック
        HealthSubcommand(plugin, scope, healthService),
        // 現金アイテム設定
        SetCashSubcommand(cashItemManager),
        // 資産履歴
        HistorySubcommand(plugin, scope, estateService),
        // 機能 有効/無効 切り替え
        EnableFeatureSubcommand(featureToggles),
        DisableFeatureSubcommand(featureToggles),
    ).associateBy { it.name }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printUsage(sender)
            return true
        }
        val sub = subcommands[args[0].lowercase()]
        if (sub == null) {
            Messages.error(sender, "不明なサブコマンドです: ${args[0]}")
            printUsage(sender)
            return true
        }
        return sub.handle(sender, args.toList())
    }

    private fun printUsage(sender: CommandSender) {
        Messages.send(sender, "使用方法: /bankop <subcommand>")
        subcommands.values.forEach { sc -> Messages.send(sender, sc.usage) }
    }

    override fun tabComplete(sender: CommandSender, label: String, args: Array<out String>): List<String> {
        // 第1引数はサブコマンド名を補完
        if (args.isEmpty()) return subcommands.keys.sorted()
        if (args.size == 1) return subcommands.keys.sorted()

        // 第2引数以降は、該当サブコマンドに委譲
        val sub = subcommands[args[0].lowercase()] ?: return emptyList()
        val subArgs = args.drop(1)
        return sub.tabComplete(sender, subArgs)
    }
}
