package red.man10.man10bank.command.op

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.vault.VaultAdminOp
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.util.UUID

/**
 * /meco <give|take|set> <player> <金額> [理由] — 管理用の電子マネー残高操作（旧 /bankop editvault）。
 * - give: 加算 / take: 減算 / set: 絶対値設定。理由は任意（省略可）。
 * - give/take は対象がこのサーバーにオンラインのときのみ実行可能（/pay と同じ同一サーバー在席条件）。
 *   set はオフライン（ログイン履歴あり）でも実行できる唯一の正規経路（POST /api/Vault/set。VaultProvider 4.5）。
 * - 操作内容（理由）は、対象がこのサーバーにオンラインなら本人にも通知する。
 *   set のオフライン対象へはリアルタイム通知が不可能なため通知しない（仕様）。
 */
class MecoCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vaultService: VaultService,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = true,
    allowGeneralUser = true,
    requiredPermission = "man10bank.admin",
) {

    private companion object {
        const val USAGE = "使い方: /meco <give|take|set> <player> <金額> [理由]"
        val OPS = listOf("give", "take", "set")
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        // 理由は任意のため、最低限 <op> <player> <金額> の 3 引数があればよい。
        if (args.size < 3) {
            Messages.error(sender, USAGE)
            return true
        }

        val op = parseOp(args[0])
        if (op == null) {
            Messages.error(sender, "操作は give / take / set のいずれかを指定してください。")
            return true
        }

        val targetName = args[1]
        // 電子マネーは1円単位の整数。小数は黙って切り捨てず、コマンド層で明示的に弾く
        // （サービス層の normalize 後検証との境界ずれ＝0.5 が通過して後段で失敗、を防ぐ）。
        val amount = args[2].toLongOrNull()
        if (amount == null) {
            val message = if (args[2].toDoubleOrNull() != null) {
                "金額は整数（1円単位）で指定してください。小数は使えません。"
            } else {
                "金額は数値で指定してください。"
            }
            Messages.error(sender, message)
            return true
        }
        // give/take は正の数、set は 0 以上（0 は残高ゼロ設定として有効）。
        if (op == VaultAdminOp.SET) {
            if (amount < 0L) {
                Messages.error(sender, "金額が不正です。0円以上を指定してください。")
                return true
            }
        } else if (amount <= 0L) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }

        // 理由は任意。未指定（空欄）の場合は各メッセージから「理由」表記を省く。
        val note = args.drop(3).joinToString(" ").trim()

        // 対象の解決: give/take はこのサーバー在席が必須、set はオフライン（ログイン履歴あり）でも可。
        val target = resolveTarget(sender, op, targetName) ?: return true

        scope.launch {
            runOperation(sender, op, target.uniqueId, target.name ?: targetName, amount, note)
        }
        return true
    }

    private fun parseOp(raw: String): VaultAdminOp? = when (raw.lowercase()) {
        "give" -> VaultAdminOp.GIVE
        "take" -> VaultAdminOp.TAKE
        "set" -> VaultAdminOp.SET
        else -> null
    }

    /** 操作種別に応じて対象プレイヤーを解決する。条件を満たさない場合はエラー送信し null を返す。 */
    private fun resolveTarget(sender: CommandSender, op: VaultAdminOp, targetName: String): OfflinePlayer? {
        if (op == VaultAdminOp.SET) {
            val cached = Bukkit.getOfflinePlayerIfCached(targetName)
            if (cached == null) {
                Messages.error(sender, "${targetName}のログイン履歴が見つかりませんでした。")
            }
            return cached
        }
        val online = plugin.server.getPlayerExact(targetName)
        if (online == null) {
            Messages.error(sender, "${targetName} はこのサーバーにオンラインではないため give/take できません（set はオフラインでも可）。")
        }
        return online
    }

    private suspend fun runOperation(
        sender: CommandSender,
        op: VaultAdminOp,
        targetUuid: UUID,
        targetName: String,
        amount: Long,
        note: String,
    ) {
        val amountValue = amount.toDouble()
        val displayNote = "管理者(${sender.name})による電子マネー${opLabel(op)}" + if (note.isBlank()) "" else ": $note"
        val result = vaultService.adminOperate(targetUuid, op, amountValue, technicalNote(op), displayNote)

        if (result.isFailure) {
            Messages.error(plugin, sender, "電子マネーの${opLabel(op)}に失敗しました: ${result.errorMessage()}")
            return
        }

        val newBalance = result.getOrNull()?.balance ?: 0.0
        Messages.send(plugin, sender, senderMessage(op, targetName, amountValue, newBalance, note))

        // 対象が（このサーバーに）オンラインなら本人へ通知する。
        plugin.server.getPlayer(targetUuid)?.let { online ->
            Messages.send(plugin, online, targetMessage(op, amountValue, newBalance, note))
        }
    }

    private fun opLabel(op: VaultAdminOp): String = when (op) {
        VaultAdminOp.GIVE -> "付与"
        VaultAdminOp.TAKE -> "徴収"
        VaultAdminOp.SET -> "残高設定"
    }

    private fun technicalNote(op: VaultAdminOp): String = when (op) {
        VaultAdminOp.GIVE -> "AdminGiveVault"
        VaultAdminOp.TAKE -> "AdminTakeVault"
        VaultAdminOp.SET -> "AdminSetVault"
    }

    /** 理由の表示用サフィックス。空欄なら何も付けない。 */
    private fun reasonSuffix(note: String): String = if (note.isBlank()) "" else " 理由: $note"

    private fun senderMessage(op: VaultAdminOp, targetName: String, amount: Double, newBalance: Double, note: String): String =
        when (op) {
            VaultAdminOp.GIVE ->
                "電子マネーを付与しました。対象: $targetName 付与額: ${BalanceFormats.coloredYen(amount)} 変更後残高: ${BalanceFormats.coloredYen(newBalance)}${reasonSuffix(note)}"
            VaultAdminOp.TAKE ->
                "電子マネーを徴収しました。対象: $targetName 徴収額: ${BalanceFormats.coloredYen(amount)} 変更後残高: ${BalanceFormats.coloredYen(newBalance)}${reasonSuffix(note)}"
            VaultAdminOp.SET ->
                "電子マネー残高を設定しました。対象: $targetName 変更後残高: ${BalanceFormats.coloredYen(newBalance)}${reasonSuffix(note)}"
        }

    // 対象プレイヤーへの通知では運営の MinecraftID は伏せ、「運営により〜」と表示する。
    private fun targetMessage(op: VaultAdminOp, amount: Double, newBalance: Double, note: String): String =
        when (op) {
            VaultAdminOp.GIVE ->
                "運営から電子マネー ${BalanceFormats.coloredYen(amount)} を受け取りました。${reasonSuffix(note)}"
            VaultAdminOp.TAKE ->
                "運営により電子マネー ${BalanceFormats.coloredYen(amount)} が引かれました。${reasonSuffix(note)}"
            VaultAdminOp.SET ->
                "運営により電子マネー残高が ${BalanceFormats.coloredYen(newBalance)} に設定されました。${reasonSuffix(note)}"
        }

    override fun tabComplete(sender: CommandSender, label: String, args: Array<out String>): List<String> =
        when (args.size) {
            1 -> OPS
            2 -> plugin.server.onlinePlayers.map { it.name }.sorted()
            else -> emptyList()
        }
}
