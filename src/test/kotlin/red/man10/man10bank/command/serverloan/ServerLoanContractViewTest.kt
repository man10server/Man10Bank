package red.man10.man10bank.command.serverloan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import red.man10.man10bank.api.model.response.ServerLoanContract
import red.man10.man10bank.api.model.response.ServerLoanLinkRequest
import red.man10.man10bank.api.model.response.ServerLoanMember
import java.util.UUID

@DisplayName("ServerLoanContractView のテスト")
class ServerLoanContractViewTest {

    private val owner = UUID.fromString("9c4161a9-0f5f-4317-835c-0bb196a7defa")
    private val member = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    @DisplayName("誰とも共有していない契約は、本人の契約で参加者なしと表示する")
    fun ownContractWithoutMembers() {
        val contract = ServerLoanContract(ownerUuid = owner.toString(), ownerPlayer = "owner")

        assertFalse(ServerLoanContractView.isShared(contract))
        assertFalse(ServerLoanContractView.isMember(contract, owner))
        assertEquals(
            listOf("§b契約の本垢: §fowner §7（owner 自身の契約）", "§b参加者: §7なし"),
            ServerLoanContractView.lines(contract, owner, "owner"),
        )
    }

    @Test
    @DisplayName("参加者から見ると、本垢の契約に参加していると表示する")
    fun memberSeesOwnersContract() {
        val contract = ServerLoanContract(
            ownerUuid = owner.toString().uppercase(),
            ownerPlayer = "owner",
            members = listOf(ServerLoanMember(uuid = member.toString(), player = "member", linkedBy = "admin")),
        )

        assertTrue(ServerLoanContractView.isShared(contract))
        assertTrue(ServerLoanContractView.isMember(contract, member))
        assertFalse(ServerLoanContractView.isMember(contract, owner))
        assertEquals(
            listOf(
                "§b契約の本垢: §fowner §7（member は参加者）",
                "§b参加者:",
                "§7- §fmember §8(紐付け: admin)",
            ),
            ServerLoanContractView.lines(contract, member, "member"),
        )
    }

    @Test
    @DisplayName("承認待ちの申請と、自分が出した申請を表示する。名前が無ければUUIDを出す")
    fun pendingAndOutgoingRequests() {
        val contract = ServerLoanContract(
            ownerUuid = owner.toString(),
            ownerPlayer = "owner",
            pendingRequests = listOf(ServerLoanMember(uuid = member.toString(), player = "")),
            outgoingRequest = ServerLoanLinkRequest(ownerUuid = member.toString(), ownerPlayer = "other"),
        )

        assertEquals(
            listOf(
                "§b契約の本垢: §fowner §7（owner 自身の契約）",
                "§b参加者: §7なし",
                "§e承認待ちの参加申請:",
                "§7- §f$member",
                "§e申請中: §fother §eの契約への参加（承認待ち）",
            ),
            ServerLoanContractView.lines(contract, owner, "owner"),
        )
    }
}
