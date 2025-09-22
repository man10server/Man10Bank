package red.man10.man10bank.command.loan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.LoanService
import red.man10.man10bank.ui.loan.CollateralSetupUI
import red.man10.man10bank.ui.loan.CollateralViewUI
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * プレイヤー間ローンの新規契約コマンド: /mlend <player> <金額> <返済金額> <返済日>
 * 進行:
 * 1) 貸し手が提案を作成 -> 借り手に提案メッセージ（担保設定/承認/拒否）
 * 2) 借り手が担保設定 -> コールバックで一時保存
 * 3) 借り手が承認 -> 貸し手へ確認（担保確認/最終承認/拒否）
 * 4) 最終承認 -> LoanService.create を実行
 */
class LendCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val loanService: LoanService,
) : BaseCommand(allowPlayer = true, allowConsole = false, allowGeneralUser = true) {

    data class Proposal(
        val id: String,
        val lender: UUID,
        val borrower: UUID,
        val amount: Double,           // 借入金額（情報表示用）
        val repayAmount: Double,      // 返済金額（APIへはこれを採用）
        val paybackDays: Int,
        var collaterals: List<ItemStack> = emptyList(),
        var borrowerApproved: Boolean = false,
    ) {
        fun isBorrower(uuid: UUID): Boolean = borrower == uuid
        fun isLender(uuid: UUID): Boolean = lender == uuid
    }

    companion object Store {
        private val proposals: MutableMap<String, Proposal> = mutableMapOf()

        fun createProposal(p: Proposal) {
            proposals[p.id] = p
        }

        fun get(id: String): Proposal? = proposals[id]

        fun remove(id: String) { proposals.remove(id) }

        fun isNotBorrower(p: Proposal?, uuid: UUID): Boolean = p == null || !p.isBorrower(uuid)
        fun isNotLender(p: Proposal?, uuid: UUID): Boolean = p == null || !p.isLender(uuid)
        fun isAlreadyProposed(borrower: UUID): Boolean = proposals.values.any { it.borrower == borrower }
    }

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        if (args.isEmpty()) {
            Messages.send(sender, "使用方法: /mlend <player> <金額> <返済金額> <返済日(yyyy-MM-dd|日数)> ")
            return true
        }

        return when (args[0].lowercase()) {
            "ui" -> handleBorrowerOpenUI(sender, args)
            "b-accept" -> handleBorrowerAccept(sender, args)
            "b-reject" -> handleBorrowerReject(sender, args)
            "l-view" -> handleLenderView(sender, args)
            "l-confirm" -> handleLenderConfirm(sender, args)
            "l-reject" -> handleLenderReject(sender, args)
            else -> handlePropose(sender, args)
        }
    }

    // --------------------
    // 提案作成 /mlend <player> <金額> <返済金額> <返済日>
    // --------------------
    private fun handlePropose(sender: Player, args: Array<out String>): Boolean {
        if (args.size < 4) {
            Messages.error(sender, "引数が不足しています。/mlend <player> <金額> <返済金額> <返済日>")
            return true
        }
        val amount = args[1].toDoubleOrNull()
        val repayAmount = args[2].toDoubleOrNull()
        val paybackDays = parsePaybackDays(args[3])
        val borrower = Bukkit.getPlayer(args[0])

        if (amount == null || amount <= 0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }
        if (repayAmount == null || repayAmount <= 0) {
            Messages.error(sender, "返済金額が不正です。正の数を指定してください。")
            return true
        }
        if (paybackDays == null || paybackDays <= 0) {
            Messages.error(sender, "返済日が不正です。yyyy-MM-dd または 日数で指定してください。")
            return true
        }
        if (borrower == null) {
            Messages.error(sender, "対象プレイヤーが見つかりません。オンラインのプレイヤー名を指定してください。")
            return true
        }
        if (borrower.uniqueId == sender.uniqueId && !sender.isOp) {
            Messages.error(sender, "自分自身には提案できません。")
            return true
        }
        if (isAlreadyProposed(borrower.uniqueId)) {
            Messages.error(sender, "このプレイヤーには既に提案が進行中です。承認または拒否が完了するまでお待ちください。")
            return true
        }

        val id = UUID.randomUUID().toString().substring(0, 8)
        val proposal = Proposal(
            id = id,
            lender = sender.uniqueId,
            borrower = borrower.uniqueId,
            amount = amount,
            repayAmount = repayAmount,
            paybackDays = paybackDays,
        )
        createProposal(proposal)

        // 借り手へ提案内容を送信（クリックボタン付き）
        sendBorrowerProposal(sender, borrower, proposal)
        Messages.send(sender, "§a${borrower.name} に提案を送信しました。")
        return true
    }

    private fun sendBorrowerProposal(lender: Player, borrower: Player, p: Proposal) {
        val lines = listOf(
            "§e§l[借金の提案]",
            "借入相手: ${lender.name}",
            "借入金額: ${BalanceFormats.colored(p.amount)}",
            "返済金額: ${BalanceFormats.colored(p.repayAmount)}",
            "返済期限: ${p.paybackDays} 日",
        )
        Messages.sendMultiline(borrower, lines.joinToString("\n"))

        var comp = Component.text(Messages.PREFIX)
        comp = comp.append(Component.text("§b§l§n[担保を設定]")
            .clickEvent(ClickEvent.runCommand("/mlend ui ${p.id}")))
        comp = comp.append(Component.text(" "))
        comp = comp.append(Component.text("§a§l§n[承認]")
            .clickEvent(ClickEvent.runCommand("/mlend b-accept ${p.id}")))
        comp = comp.append(Component.text(" "))
        comp = comp.append(Component.text("§c§l§n[拒否]")
            .clickEvent(ClickEvent.runCommand("/mlend b-reject ${p.id}")))
        borrower.sendMessage(comp)
    }

    // 借り手: 担保設定UI
    private fun handleBorrowerOpenUI(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotBorrower(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなた宛ではありません。")
            return true
        }
        CollateralSetupUI(sender, proposal!!.collaterals, onUpdate = { newItems: List<ItemStack> ->
            proposal.collaterals = newItems
            Messages.send(sender, "担保を更新しました（${newItems.size}件）。")
        }).open()
        return true
    }

    // 借り手: 承認
    private fun handleBorrowerAccept(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotBorrower(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなた宛ではありません。")
            return true
        }
        proposal!!.borrowerApproved = true
        val lender = Bukkit.getPlayer(proposal.lender)
        if (lender == null) {
            Messages.warn(sender, "貸し手がオフラインのため、保留されました。")
            return true
        }
        sendLenderConfirmation(lender, sender, proposal)
        Messages.send(sender, "§a承認しました。貸し手の最終承認待ちです。")
        return true
    }

    // 借り手: 拒否
    private fun handleBorrowerReject(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotBorrower(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなた宛ではありません。")
            return true
        }
        remove(id)
        val lender = Bukkit.getPlayer(proposal!!.lender)
        if (lender != null) Messages.warn(lender, "${sender.name} に拒否されました。")
        Messages.send(sender, "§6提案を拒否しました。")
        return true
    }

    private fun sendLenderConfirmation(lender: Player, borrower: Player, p: Proposal) {
        val lines = listOf(
            "§e§l[借金の最終確認]",
            "貸出相手: ${borrower.name}",
            "借入金額: ${BalanceFormats.colored(p.amount)}",
            "返済金額: ${BalanceFormats.colored(p.repayAmount)}",
            "返済期限: ${p.paybackDays} 日",
            "担保点数: ${p.collaterals.size}",
        )
        Messages.sendMultiline(lender, lines.joinToString("\n"))

        var comp = Component.text(Messages.PREFIX)
        comp = comp.append(Component.text("§b§l§n[担保を確認]")
            .clickEvent(ClickEvent.runCommand("/mlend l-view ${p.id}")))
        comp = comp.append(Component.text(" "))
        comp = comp.append(Component.text("§a§l§n[最終承認]")
            .clickEvent(ClickEvent.runCommand("/mlend l-confirm ${p.id}")))
        comp = comp.append(Component.text(" "))
        comp = comp.append(Component.text("§c§l§n[拒否]")
            .clickEvent(ClickEvent.runCommand("/mlend l-reject ${p.id}")))
        lender.sendMessage(comp)
    }

    // 貸し手: 担保確認UI
    private fun handleLenderView(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotLender(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなたが貸し手ではありません。")
            return true
        }
        CollateralViewUI(sender, proposal!!.collaterals).open()
        return true
    }

    // 貸し手: 最終承認 -> サービス呼び出し
    private fun handleLenderConfirm(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotLender(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなたが貸し手ではありません。")
            return true
        }
        val borrower = Bukkit.getPlayer(proposal!!.borrower)
        if (borrower == null) {
            Messages.error(sender, "借り手がオフラインのため、実行できません。")
            return true
        }
        val collateral = proposal.collaterals.firstOrNull()

        scope.launch {
            val result = loanService.create(sender, borrower, proposal.repayAmount, proposal.paybackDays, collateral)
            if (result.isSuccess) {
                val loan = result.getOrNull()
                Messages.send(plugin, sender, "§aローンを作成しました。ID: ${loan?.id ?: "不明"}")
                Messages.send(plugin, borrower, "§a借入が確定しました。金額: ${BalanceFormats.colored(proposal.repayAmount)}")
                remove(id)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "ローン作成に失敗しました。"
                Messages.error(plugin, sender, msg)
                remove(id)
            }
        }
        return true
    }

    // 貸し手: 拒否
    private fun handleLenderReject(sender: Player, args: Array<out String>): Boolean {
        val id = args.getOrNull(1) ?: return true.also { Messages.error(sender, "IDが指定されていません。") }
        val proposal = get(id)
        if (isNotLender(proposal, sender.uniqueId)) {
            Messages.error(sender, "この提案は見つからないか、あなたが貸し手ではありません。")
            return true
        }
        remove(id)
        val borrower = Bukkit.getPlayer(proposal!!.borrower)
        if (borrower != null) Messages.warn(borrower, "貸し手に拒否されました。")
        Messages.send(sender, "§6提案を拒否しました。")
        return true
    }

    // 返済日: yyyy-MM-dd または 日数
    private fun parsePaybackDays(arg: String): Int? {
        arg.toIntOrNull()?.let { return it }
        return try {
            val date = LocalDate.parse(arg)
            val today = LocalDate.now(ZoneOffset.UTC)
            val days = ChronoUnit.DAYS.between(today, date).toInt()
            if (days <= 0) null else days
        } catch (e: Exception) {
            null
        }
    }
}
