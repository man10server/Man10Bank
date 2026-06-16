package red.man10.man10bank.service.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

@DisplayName("VaultCache のテスト（version方式・楽観更新）")
class VaultCacheTest {

    private val uuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @Test
    @DisplayName("preload で残高/versionをセットし get/contains で参照できる")
    fun preloadAndGet() {
        val c = VaultCache()
        assertFalse(c.contains(uuid))
        assertEquals(0.0, c.get(uuid))
        c.preload(uuid, 1000.0, 5)
        assertTrue(c.contains(uuid))
        assertEquals(1000.0, c.get(uuid))
        assertEquals(5L, c.getVersion(uuid))
    }

    @Test
    @DisplayName("has: キャッシュ済みは残高比較、未キャッシュは false")
    fun has() {
        val c = VaultCache()
        assertFalse(c.has(uuid, 1.0))
        c.preload(uuid, 500.0, 1)
        assertTrue(c.has(uuid, 500.0))
        assertTrue(c.has(uuid, 300.0))
        assertFalse(c.has(uuid, 501.0))
    }

    @Test
    @DisplayName("optimisticDeposit: キャッシュ済みは加算、未キャッシュは null（作成しない）")
    fun optimisticDeposit() {
        val c = VaultCache()
        assertNull(c.optimisticDeposit(uuid, 100.0))
        assertFalse(c.contains(uuid))

        c.preload(uuid, 100.0, 1)
        assertEquals(250.0, c.optimisticDeposit(uuid, 150.0))
        assertEquals(250.0, c.get(uuid))
        // version は据え置き（権威確定は write-through/push で）
        assertEquals(1L, c.getVersion(uuid))
    }

    @Test
    @DisplayName("optimisticWithdraw: 十分なら減算、不足/未キャッシュは null で不変")
    fun optimisticWithdraw() {
        val c = VaultCache()
        assertNull(c.optimisticWithdraw(uuid, 50.0)) // 未キャッシュ

        c.preload(uuid, 100.0, 1)
        assertNull(c.optimisticWithdraw(uuid, 150.0)) // 不足
        assertEquals(100.0, c.get(uuid)) // 不変

        assertEquals(60.0, c.optimisticWithdraw(uuid, 40.0))
        assertEquals(60.0, c.get(uuid))
    }

    @Test
    @DisplayName("applyAuthoritative: version > 現在 のときだけ反映（冪等・順序安全）")
    fun applyAuthoritative() {
        val c = VaultCache()
        // 未キャッシュ（オフライン）への push は no-op
        assertFalse(c.applyAuthoritative(uuid, 999.0, 10))
        assertFalse(c.contains(uuid))

        c.preload(uuid, 100.0, 5)
        // 新しい version は反映
        assertTrue(c.applyAuthoritative(uuid, 200.0, 6))
        assertEquals(200.0, c.get(uuid))
        assertEquals(6L, c.getVersion(uuid))
        // 同じ/古い version は無視（二重受信・順序逆転に安全）
        assertFalse(c.applyAuthoritative(uuid, 123.0, 6))
        assertFalse(c.applyAuthoritative(uuid, 123.0, 3))
        assertEquals(200.0, c.get(uuid))
    }

    @Test
    @DisplayName("reconcile: version >= 現在 で反映し楽観ドリフトを矯正（同version可）")
    fun reconcile() {
        val c = VaultCache()
        c.preload(uuid, 100.0, 5)
        // 楽観更新で残高だけずれた状態（version 据え置き）
        c.optimisticWithdraw(uuid, 30.0) // 70.0, version 5
        // 権威再取得（同 version だが本当の残高は 100）→ 矯正される
        c.reconcile(uuid, 100.0, 5)
        assertEquals(100.0, c.get(uuid))
        // 古い version は無視
        c.reconcile(uuid, 1.0, 4)
        assertEquals(100.0, c.get(uuid))
        // 未キャッシュは作成しない
        val other = UUID.randomUUID()
        c.reconcile(other, 50.0, 9)
        assertFalse(c.contains(other))
    }

    @Test
    @DisplayName("evict で退避できる")
    fun evict() {
        val c = VaultCache()
        c.preload(uuid, 100.0, 1)
        c.evict(uuid)
        assertFalse(c.contains(uuid))
    }
}
