package red.man10.man10bank.service.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("VaultWriteQueue のテスト")
class VaultWriteQueueTest {

    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun op(id: String) = VaultWriteQueue.VaultQueuedOperation(
        operationId = id, serverName = "srv", uuid = uuid,
        type = VaultWriteQueue.Type.PROVIDER_WITHDRAW, amount = 100,
        pluginName = "shop", reason = "buy", createdAtMillis = 0,
    )

    @Test
    @DisplayName("上限まで enqueue でき、超過すると false で健全でなくなる")
    fun enqueueUpToLimit() {
        val q = VaultWriteQueue(maxPending = 2)
        assertTrue(q.enqueue(op("a")))
        assertTrue(q.enqueue(op("b")))
        assertFalse(q.enqueue(op("c"))) // 上限到達で拒否
        assertEquals(2, q.size())
        assertFalse(q.isHealthy())
    }

    @Test
    @DisplayName("FIFO で取り出せる")
    fun fifo() {
        val q = VaultWriteQueue(maxPending = 10)
        q.enqueue(op("a")); q.enqueue(op("b"))
        assertEquals("a", q.pollFirst()?.operationId)
        assertEquals("b", q.pollFirst()?.operationId)
        assertNull(q.pollFirst())
    }

    @Test
    @DisplayName("requeueFirst で先頭へ戻し同一 operationId で再送できる")
    fun requeueFirst() {
        val q = VaultWriteQueue(maxPending = 10)
        q.enqueue(op("a")); q.enqueue(op("b"))
        val first = q.pollFirst()!!
        q.requeueFirst(first)
        assertEquals("a", q.pollFirst()?.operationId) // 先頭へ戻っている
    }

    @Test
    @DisplayName("空キューは健全")
    fun emptyHealthy() {
        val q = VaultWriteQueue(maxPending = 1)
        assertTrue(q.isHealthy())
        assertEquals(0, q.size())
    }
}
