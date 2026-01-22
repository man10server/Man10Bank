package red.man10.man10bank.command.op.sub

import org.bukkit.command.CommandSender
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.FeatureToggleService
import red.man10.man10bank.util.Messages

/**
 * /bankop disable <機能> - 機能を無効化
 * 引数省略時は現在停止中の機能一覧を表示。
 */
class DisableFeatureSubcommand(
    private val toggles: FeatureToggleService,
) : BankOpSubcommand {

    override val name: String = "disable"
    override val usage: String = "/bankop disable <cheque|transaction|serverloan|loan|atm|all> - 機能を無効化"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        val featureArg = args.getOrNull(1)?.lowercase()
        if (featureArg.isNullOrBlank()) {
            // 引数なし: 現在 起動中(有効) の機能を表示
            printEnabledList(sender)
            return true
        }

        if (featureArg == "all") {
            toggles.disableAll()
            Messages.send(sender, "全機能を無効化しました。")
            printDisabledList(sender)
            return true
        }

        val feature = FeatureToggleService.Feature.fromArg(featureArg)
        if (feature == null) {
            Messages.error(sender, "不明な機能名です。指定可能: cheque, transaction, serverloan, loan, atm, all")
            return true
        }
        toggles.setEnabled(feature, false)
        Messages.send(sender, "${feature.displayNameJa} を無効化しました。")
        printDisabledList(sender)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        // このサブコマンドは引数1つ（機能名）を想定。
        if (args.size <= 1) {
            return FeatureToggleService.Feature.entries
                .filter { toggles.isEnabled(it) }
                .map { it.key }
                .sorted()
        }
        return emptyList()
    }

    private fun printEnabledList(sender: CommandSender) {
        val list = FeatureToggleService.Feature.entries.filter { toggles.isEnabled(it) }
        if (list.isEmpty()) {
            Messages.send(sender, "現在、起動中の機能はありません。")
            return
        }
        Messages.send(sender, "起動中の機能:")
        list.forEach { feature ->
            Messages.send(sender, "・${feature.key}: ${feature.displayNameJa}")
        }
    }

    private fun printDisabledList(sender: CommandSender) {
        val list = toggles.disabledFeatures()
        if (list.isEmpty()) {
            Messages.send(sender, "現在、停止中の機能はありません。")
            return
        }
        Messages.warn(sender, "停止中の機能:")
        list.forEach { feature ->
            Messages.send(sender, "・${feature.key}: ${feature.displayNameJa}")
        }
    }
}
