package red.man10.man10bank.command.op.sub.loan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.service.LoanService
import red.man10.man10bank.service.RecoveryLedger
import red.man10.man10bank.service.RecoveryLedger.Record
import red.man10.man10bank.ui.loan.CollateralViewUI
import red.man10.man10bank.util.DateFormats
import red.man10.man10bank.util.ItemStackBase64
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.util.UUID

/**
 * /bankop loan recovery ... - 返せなかった手形・小切手・担保（復元台帳）の確認・復元・破棄。
 * - give は台帳を「復元済み」に保存できてからアイテムを渡す（同じ記録から二重に渡さない）。
 * - ローン作成結果不明の記録は、ローンが作成済みなら担保はローン側にあり、返すと二重になる。
 *   そのため give の前に借り手のローンを照会し、作成済みなら渡さない。
 */
class LoanRecoveryCommand(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val ledger: RecoveryLedger,
    private val loanService: LoanService,
) {
    val usageLines: List<String> = listOf(
        "/bankop loan recovery list [player] - 未対応の記録を一覧",
        "/bankop loan recovery info <id> - 記録の詳細とアイテムを確認",
        "/bankop loan recovery give <id> [player] - アイテムを渡して復元済みにする（省略時は本来の受取人）",
        "/bankop loan recovery discard <id> <理由> - アイテムを渡さずに破棄する",
    )

    /** [args] は "recovery" より後ろの引数。メインスレッドで呼ぶこと。 */
    fun handle(sender: CommandSender, args: List<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "list" -> list(sender, args.getOrNull(1))
            "info" -> info(sender, args.getOrNull(1))
            "give" -> give(sender, args.getOrNull(1), args.getOrNull(2))
            "discard" -> discard(sender, args.getOrNull(1), args.drop(2).joinToString(" ").trim())
            else -> usageLines.forEach { Messages.send(sender, it) }
        }
    }

    fun tabComplete(args: List<String>): List<String> {
        val sub = args.getOrNull(0)?.lowercase()
        return when {
            args.size <= 1 -> listOf("list", "info", "give", "discard")
            args.size == 2 && sub == "list" -> Bukkit.getOnlinePlayers().map { it.name }.sorted()
            args.size == 2 && sub in setOf("info", "give", "discard") -> ledger.list().map { it.id }
            args.size == 3 && sub == "give" -> Bukkit.getOnlinePlayers().map { it.name }.sorted()
            else -> emptyList()
        }
    }

    private fun list(sender: CommandSender, playerName: String?) {
        val records = ledger.list()
            .filter { playerName == null || nameOf(it.ownerUuid).equals(playerName, ignoreCase = true) }
        if (records.isEmpty()) {
            Messages.send(sender, "未対応の記録はありません。")
            return
        }
        val lines = mutableListOf("§e§l[復元台帳] 未対応 ${records.size}件")
        records.forEach { r ->
            val loan = r.loanId?.let { " §fローン:$it" } ?: ""
            lines += "§bID:${r.id} §f${r.type.label} §f受取人:${nameOf(r.ownerUuid)}$loan §f件数:${r.itemCount} §7${DateFormats.toDateTime(r.createdAt)}"
        }
        Messages.sendMultiline(sender, lines.joinToString("\n"))
    }

    private fun info(sender: CommandSender, idArg: String?) {
        val record = findRecord(sender, idArg) ?: return
        val lines = mutableListOf(
            "§e§l[復元台帳] ${record.id}",
            "種類: ${record.type.label} / 状態: ${record.status.label}",
            "受取人: ${nameOf(record.ownerUuid)} (${record.owner})",
        )
        record.lenderUuid?.let { lines += "貸し手: ${nameOf(it)} ($it)" }
        record.loanId?.let { lines += "ローンID: $it" }
        lines += "件数: ${record.itemCount} / 記録日時: ${DateFormats.toDateTime(record.createdAt)}"
        lines += "詳細: ${record.detail}"
        if (record.status != RecoveryLedger.Status.PENDING) {
            val to = record.resolvedTo?.let { " 受取者: ${nameOf(UUID.fromString(it))}" } ?: ""
            val note = record.resolveNote?.let { " 理由: $it" } ?: ""
            lines += "処理: ${record.resolvedAt?.let { DateFormats.toDateTime(it) } ?: "-"} 実行者: ${record.resolvedBy ?: "-"}$to$note"
        } else if (record.type == RecoveryLedger.Type.LOAN_CREATE_INDETERMINATE) {
            lines += "§6give では借り手のローンを照会し、作成済みなら渡しません。作成済みの場合は discard し、" +
                "/bankop loan note <ローンID> で受け取った手形を貸し手へ渡してください。"
        }
        Messages.sendMultiline(sender, lines.joinToString("\n"))

        val items = decode(sender, record) ?: return
        items.forEach { Messages.send(sender, " - ${it.type.name} ×${it.amount}") }
        // ゲーム内なら閲覧専用の画面で中身（説明文やエンチャント等）を確認できるようにする
        if (sender is Player) CollateralViewUI(sender, items).open()
    }

    private fun give(sender: CommandSender, idArg: String?, playerArg: String?) {
        val record = findRecord(sender, idArg) ?: return
        if (record.status != RecoveryLedger.Status.PENDING) {
            Messages.error(sender, "この記録は既に${record.status.label}です。")
            return
        }
        val items = decode(sender, record) ?: return
        val targetUuid = if (playerArg != null) {
            Bukkit.getPlayerExact(playerArg)?.uniqueId ?: run {
                Messages.error(sender, "オンラインのプレイヤーを指定してください: $playerArg")
                return
            }
        } else {
            record.ownerUuid
        }
        if (Bukkit.getPlayer(targetUuid) == null) {
            Messages.error(sender, "${nameOf(targetUuid)} はオフラインです。オンラインのときに実行するか、受け取るプレイヤーを指定してください。")
            return
        }
        if (record.type != RecoveryLedger.Type.LOAN_CREATE_INDETERMINATE) {
            deliver(sender, record.id, targetUuid, items)
            return
        }

        val lender = record.lenderUuid ?: run {
            Messages.error(sender, "貸し手が記録されていないため、ローンの作成有無を確認できません。")
            return
        }
        Messages.send(sender, "ローンが作成されていないかを照会しています…")
        scope.launch {
            val found = loanService.findCreatedLoan(record.ownerUuid, lender, record.itemsBase64)
            plugin.server.scheduler.runTask(plugin, Runnable {
                val loan = found.getOrElse {
                    Messages.error(sender, "ローンを照会できなかったため、渡していません: ${found.errorMessage()}")
                    return@Runnable
                }
                if (loan != null) {
                    Messages.error(
                        sender,
                        "このローンはローンID ${loan.id} として作成済みです。担保はローン側にあるため、渡すと二重になります。" +
                            "/bankop loan recovery discard ${record.id} 作成済み で破棄し、/bankop loan note ${loan.id} で手形を受け取って貸し手へ渡してください。"
                    )
                    return@Runnable
                }
                deliver(sender, record.id, targetUuid, items)
            })
        }
    }

    // メインスレッドで呼ぶ。台帳を「復元済み」に保存できた場合だけ渡す。
    private fun deliver(sender: CommandSender, id: String, targetUuid: UUID, items: List<ItemStack>) {
        val target = Bukkit.getPlayer(targetUuid) ?: run {
            Messages.error(sender, "${nameOf(targetUuid)} はオフラインになったため、渡していません。")
            return
        }
        val restored = ledger.markRestored(id, sender.name, target.uniqueId).getOrElse {
            Messages.error(sender, "復元済みにできなかったため、渡していません: ${it.message}")
            return
        }
        items.forEach { stack ->
            target.inventory.addItem(stack).values.forEach { target.world.dropItemNaturally(target.location, it) }
        }
        plugin.logger.info(
            "復元台帳のアイテムを渡しました 記録ID=${id} 種類=${restored.type.name} 受取者=${target.name}(${target.uniqueId}) " +
                "実行者=${sender.name} 件数=${items.size}"
        )
        Messages.send(sender, "記録 ${id} のアイテム${items.size}件を ${target.name} に渡しました。")
        if (target !== sender) {
            Messages.send(target, "運営が、返せなかった${restored.type.itemLabel}を復元しました（${items.size}件）。")
        }
    }

    private fun discard(sender: CommandSender, idArg: String?, reason: String) {
        val record = findRecord(sender, idArg) ?: return
        if (reason.isBlank()) {
            Messages.error(sender, "破棄する理由を指定してください。例: /bankop loan recovery discard ${record.id} ローン作成済み")
            return
        }
        ledger.markDiscarded(record.id, sender.name, reason).fold(
            onSuccess = {
                plugin.logger.info("復元台帳の記録を破棄しました 記録ID=${record.id} 種類=${record.type.name} 実行者=${sender.name} 理由=${reason}")
                Messages.send(sender, "記録 ${record.id} を破棄しました。")
            },
            onFailure = { Messages.error(sender, "破棄できませんでした: ${it.message}") },
        )
    }

    private fun findRecord(sender: CommandSender, idArg: String?): Record? {
        if (idArg.isNullOrBlank()) {
            Messages.error(sender, "記録IDを指定してください（/bankop loan recovery list で確認できます）。")
            return null
        }
        return ledger.get(idArg.lowercase()) ?: run {
            Messages.error(sender, "記録が見つかりません: $idArg")
            null
        }
    }

    private fun decode(sender: CommandSender, record: Record): List<ItemStack>? =
        runCatching { ItemStackBase64.decodeItems(record.itemsBase64) }.getOrElse {
            Messages.error(sender, "記録 ${record.id} のアイテムデータを読み込めませんでした: ${it.message}")
            null
        }

    private fun nameOf(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
}
