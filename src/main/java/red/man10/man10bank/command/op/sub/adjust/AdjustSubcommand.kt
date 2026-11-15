package red.man10.man10bank.command.op.sub.adjust

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.op.BankOpSubcommand
import red.man10.man10bank.service.PlayerLookupService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage

/**
 * /bankop give|take|set <bank|vault|serverloan> <player> <金額> [理由]
 * - give: 加算、take: 減算、set: 指定額に設定。金額は円単位（小数点以下切り捨て）。
 * - 対象はオフラインでも可（名前はオンライン→キャッシュ→名簿で解決）。
 * - 理由は任意。銀行は取引履歴の表示メモに「管理者(名前)による銀行残高加算: 理由」の形で残る。
 * - 変更前後の値を表示するので、絶対額を計算してから指定する必要がない。
 */
class AdjustSubcommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val verb: AdjustVerb,
    private val accounts: List<AdjustableAccount>,
    private val playerLookup: PlayerLookupService,
) : BankOpSubcommand {
    override val name: String = verb.key
    override val usage: String =
        "/bankop ${verb.key} <${accounts.joinToString("|") { it.key }}> <player> <金額> [理由] - ${verb.description}"

    override fun handle(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 4) {
            Messages.error(sender, "使い方: $usage")
            return true
        }
        val account = accounts.firstOrNull { it.key.equals(args[1], ignoreCase = true) }
        if (account == null) {
            Messages.error(sender, "口座種別が不正です。指定可能: ${accounts.joinToString(", ") { it.key }}")
            return true
        }
        val targetName = args[2]
        val amount = parseAmount(args[3])
        if (amount == null) {
            Messages.error(sender, "金額は数値（円）で指定してください。")
            return true
        }
        if (verb == AdjustVerb.SET && amount < 0.0) {
            Messages.error(sender, "金額が不正です。0円以上を指定してください。")
            return true
        }
        if (verb != AdjustVerb.SET && amount <= 0.0) {
            Messages.error(sender, "金額が不正です。1円以上を指定してください。")
            return true
        }
        account.unavailableReason()?.let {
            Messages.error(sender, it)
            return true
        }
        val reason = args.drop(4).joinToString(" ").trim()

        scope.launch {
            val resolved = playerLookup.resolve(targetName)
            val target = resolved.getOrNull()
            if (target == null) {
                Messages.error(plugin, sender, playerLookup.failureMessage(targetName, resolved))
                return@launch
            }

            val currentResult = account.current(target)
            val current = currentResult.getOrNull()
            if (current == null) {
                Messages.error(plugin, sender, "${account.labelJa}の取得に失敗しました: ${currentResult.errorMessage()}")
                return@launch
            }

            val plan = verb.plan(current, amount, account.floorAtZero).getOrElse {
                Messages.error(plugin, sender, it.message ?: "金額が不正です。")
                return@launch
            }
            if (plan.delta == 0.0) {
                Messages.send(plugin, sender, "${account.labelJa}に変更はありません。対象: ${target.name} 現在: ${BalanceFormats.coloredYen(current)}")
                return@launch
            }

            val applied = account.apply(sender.name, target, verb, plan, reason)
            val after = applied.getOrNull()
            if (after == null) {
                Messages.error(plugin, sender, "${account.labelJa}の${verb.labelJa}に失敗しました: ${applied.errorMessage()}")
                return@launch
            }
            val deltaText = if (plan.delta >= 0.0) "+${BalanceFormats.coloredYen(plan.delta)}" else "-${BalanceFormats.coloredYen(-plan.delta)}"
            val reasonText = reason.ifBlank { "（なし）" }
            Messages.send(
                plugin,
                sender,
                "§a${account.labelJa}を${verb.labelJa}しました。§r対象: ${target.name} 変更: $deltaText " +
                        "変更前: ${BalanceFormats.coloredYen(plan.before)} 変更後: ${BalanceFormats.coloredYen(after)} 理由: $reasonText"
            )
        }
        return true
    }

    /** 金額を円単位に正規化する（小数点以下切り捨て）。数値でなければ null。 */
    private fun parseAmount(raw: String): Double? {
        val value = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        return value.toLong().toDouble()
    }

    override fun tabComplete(sender: CommandSender, args: List<String>): List<String> = when (args.size) {
        0, 1 -> accounts.map { it.key }.sorted()
        2 -> Bukkit.getOnlinePlayers().map { it.name }.sorted()
        else -> emptyList()
    }
}
