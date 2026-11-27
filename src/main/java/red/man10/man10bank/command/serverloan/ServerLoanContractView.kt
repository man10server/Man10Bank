package red.man10.man10bank.command.serverloan

import red.man10.man10bank.api.model.response.ServerLoanContract
import java.util.UUID

/**
 * サーバーローンの契約の共有（リンク）状態を、チャット表示用の行にする。
 * /mrevo link（本人向け）と /bankop serverloan info（運営向け）で共通に使う。
 */
object ServerLoanContractView {

    /** 契約に、本垢以外の参加者がいるかどうか。 */
    fun isShared(contract: ServerLoanContract): Boolean = contract.members.isNotEmpty()

    /** viewer が、他のプレイヤーの契約に参加しているかどうか。 */
    fun isMember(contract: ServerLoanContract, viewer: UUID): Boolean =
        !contract.ownerUuid.equals(viewer.toString(), ignoreCase = true)

    /**
     * 契約の状態の行。
     * @param viewer 表示対象のプレイヤー（この人から見た契約）。
     * @param viewerName 表示対象のプレイヤー名。
     */
    fun lines(contract: ServerLoanContract, viewer: UUID, viewerName: String): List<String> {
        val lines = mutableListOf<String>()
        val member = isMember(contract, viewer)
        val role = if (member) "§7（${viewerName} は参加者）" else "§7（${viewerName} 自身の契約）"
        lines += "§b契約の本垢: §f${name(contract.ownerPlayer, contract.ownerUuid)} $role"

        if (contract.members.isEmpty()) {
            lines += "§b参加者: §7なし"
        } else {
            lines += "§b参加者:"
            contract.members.forEach { lines += "§7- §f${name(it.player, it.uuid)} §8(紐付け: ${it.linkedBy})" }
        }

        if (contract.pendingRequests.isNotEmpty()) {
            lines += "§e承認待ちの参加申請:"
            contract.pendingRequests.forEach { lines += "§7- §f${name(it.player, it.uuid)}" }
        }

        contract.outgoingRequest?.let {
            lines += "§e申請中: §f${name(it.ownerPlayer, it.ownerUuid)} §eの契約への参加（承認待ち）"
        }
        return lines
    }

    /** 名簿に名前が無い場合は UUID を表示する。 */
    private fun name(player: String, uuid: String): String = player.ifBlank { uuid }
}
