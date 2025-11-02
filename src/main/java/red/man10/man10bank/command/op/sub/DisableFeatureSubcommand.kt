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
            printDisabledList(sender)
            return true
        }

        if (featureArg == "all") {
            toggles.disableAll()
            Messages.send(sender, "全機能を無効化しました。")
            printDisabledList(sender)
            return true
        }

        val feature = parseFeature(featureArg)
        if (feature == null) {
            Messages.error(sender, "不明な機能名です。指定可能: cheque, transaction, serverloan, loan, atm, all")
            return true
        }
        toggles.setEnabled(feature, false)
        Messages.send(sender, "${feature.displayNameJa} を無効化しました。")
        printDisabledList(sender)
        return true
    }

    private fun printDisabledList(sender: CommandSender) {
        val list = toggles.disabledFeatures()
        if (list.isEmpty()) {
            Messages.send(sender, "現在、停止中の機能はありません。")
            return
        }
        val names = list.joinToString("、") { it.displayNameJa }
        Messages.warn(sender, "停止中の機能: $names")
    }

    private fun parseFeature(arg: String): FeatureToggleService.Feature? = when (arg.lowercase()) {
        "cheque", "小切手" -> FeatureToggleService.Feature.CHEQUE
        "transaction", "取引", "deposit", "withdraw", "pay" -> FeatureToggleService.Feature.TRANSACTION
        "serverloan", "server-loan", "server_loan", "サーバーローン" -> FeatureToggleService.Feature.SERVER_LOAN
        "loan", "プレイヤーローン" -> FeatureToggleService.Feature.LOAN
        "atm" -> FeatureToggleService.Feature.ATM
        else -> null
    }
}
