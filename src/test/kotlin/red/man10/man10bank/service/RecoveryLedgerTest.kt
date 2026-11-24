package red.man10.man10bank.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import red.man10.man10bank.service.RecoveryLedger.Status
import red.man10.man10bank.service.RecoveryLedger.Type
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/**
 * 復元台帳のテスト。
 * - 記録はファイルに残り、別インスタンス（再起動後）でも読める。
 * - 同じ記録から二重に復元できない（復元済み・破棄の記録は再度処理できない）。
 */
@DisplayName("RecoveryLedger のテスト")
class RecoveryLedgerTest {

    @TempDir
    lateinit var dir: File

    private val logger = Logger.getLogger("RecoveryLedgerTest")
    private val owner = UUID.fromString("9c4161a9-0f5f-4317-835c-0bb196a7defa")
    private val lender = UUID.fromString("49c42256-2357-4963-8678-7a06e6dd3125")

    private fun ledger() = RecoveryLedger(dir, logger)

    @Test
    @DisplayName("記録した内容は別インスタンスでも読み直せる")
    fun recordIsPersisted() {
        val id = ledger().record(Type.LOAN_CREATE_INDETERMINATE, owner, "AAAA\nBBBB", 2, "詳細", loanId = null, lender = lender)
        assertNotNull(id)

        val record = ledger().get(id!!)
        assertNotNull(record)
        assertEquals(Type.LOAN_CREATE_INDETERMINATE, record!!.type)
        assertEquals(owner, record.ownerUuid)
        assertEquals(lender, record.lenderUuid)
        assertEquals("AAAA\nBBBB", record.itemsBase64)
        assertEquals(2, record.itemCount)
        assertEquals(Status.PENDING, record.status)
    }

    @Test
    @DisplayName("一覧には未対応の記録だけが出る")
    fun listShowsOnlyPending() {
        val ledger = ledger()
        val first = ledger.record(Type.NOTE_RETURN_FAILED, owner, "A", 1, "1件目", loanId = 10)!!
        val restored = ledger.record(Type.CHEQUE_RETURN_FAILED, owner, "B", 1, "2件目")!!
        val discarded = ledger.record(Type.COLLATERAL_RETURN_FAILED, owner, "C", 1, "3件目")!!
        val last = ledger.record(Type.COLLATERAL_HANDOVER_FAILED, owner, "D", 1, "4件目", loanId = 11)!!
        ledger.markRestored(restored, "op", owner).getOrThrow()
        ledger.markDiscarded(discarded, "op", "作成済みだった").getOrThrow()

        assertEquals(setOf(first, last), ledger.list().map { it.id }.toSet())
        assertEquals(4, ledger.list(status = null).size)
    }

    @Test
    @DisplayName("復元済みの記録はもう一度復元も破棄もできない")
    fun restoredRecordCannotBeResolvedAgain() {
        val ledger = ledger()
        val id = ledger.record(Type.NOTE_RETURN_FAILED, owner, "A", 1, "詳細")!!

        val restored = ledger.markRestored(id, "op", owner).getOrThrow()
        assertEquals(Status.RESTORED, restored.status)
        assertEquals("op", restored.resolvedBy)
        assertEquals(owner.toString(), restored.resolvedTo)

        assertTrue(ledger.markRestored(id, "op2", owner).isFailure)
        assertTrue(ledger.markDiscarded(id, "op2", "理由").isFailure)
        // 再起動後も復元済みのまま
        assertTrue(ledger().markRestored(id, "op3", owner).isFailure)
    }

    @Test
    @DisplayName("破棄した記録は復元できず、理由が残る")
    fun discardedRecordKeepsReason() {
        val ledger = ledger()
        val id = ledger.record(Type.LOAN_CREATE_INDETERMINATE, owner, "A", 1, "詳細", lender = lender)!!

        ledger.markDiscarded(id, "op", "ローンID 50 として作成済み").getOrThrow()

        assertTrue(ledger.markRestored(id, "op", owner).isFailure)
        val record = ledger().get(id)!!
        assertEquals(Status.DISCARDED, record.status)
        assertEquals("ローンID 50 として作成済み", record.resolveNote)
    }

    @Test
    @DisplayName("ID以外の文字列や存在しないIDでは取得できない")
    fun invalidIdReturnsNull() {
        val ledger = ledger()
        ledger.record(Type.NOTE_RETURN_FAILED, owner, "A", 1, "詳細")
        assertNull(ledger.get("../../config"))
        assertNull(ledger.get("ABCDEF12"))
        assertNull(ledger.get("00000000"))
        assertTrue(ledger.markRestored("00000000", "op", owner).isFailure)
    }

    @Test
    @DisplayName("壊れたファイルは読み飛ばす")
    fun brokenFileIsSkipped() {
        val ledger = ledger()
        val id = ledger.record(Type.NOTE_RETURN_FAILED, owner, "A", 1, "詳細")!!
        File(dir, "deadbeef.json").writeText("{ broken")

        assertEquals(listOf(id), ledger.list().map { it.id })
        assertNull(ledger.get("deadbeef"))
    }
}
