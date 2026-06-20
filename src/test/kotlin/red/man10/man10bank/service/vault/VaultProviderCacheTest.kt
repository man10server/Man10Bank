package red.man10.man10bank.service.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Provider キャッシュ(整合性モデルの中核)の単体テスト。
 * - availableBalance = confirmedBalance + pendingDelta(0 以下)。未確定入金は含めない。
 * - 減算予約は READY のときだけ、かつ availableBalance >= amount のときだけ成功する。
 * - version で古い push/再同期を捨てる。
 */
@DisplayName("VaultProviderCache のテスト")
class VaultProviderCacheTest {

    private val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now = 1_000L

    private fun readyCache(balance: Long, version: Long = 1): VaultProviderCache {
        val c = VaultProviderCache()
        c.beginLoad(uuid, now)
        c.onInitialLoad(uuid, balance, version, now + 3000, now)
        c.promoteToReady(uuid, balance, version, now)
        return c
    }

    private fun op(id: String, amount: Long) =
        VaultProviderCache.PendingVaultOperation(id, amount, VaultProviderCache.PendingSource.PROVIDER, now)

    @Test
    @DisplayName("初回ロードで WARMING_UP・visibleBalance=confirmedBalance")
    fun initialLoad() {
        val c = VaultProviderCache()
        c.beginLoad(uuid, now)
        c.onInitialLoad(uuid, 1000, 5, now + 3000, now)
        val e = c.get(uuid)!!
        assertEquals(VaultProviderCache.Status.WARMING_UP, e.status)
        assertEquals(1000, e.visibleBalance())
        assertEquals(1000, e.availableBalance())
        assertEquals(0, e.pendingDelta())
    }

    @Test
    @DisplayName("WARMING_UP では減算予約できない")
    fun reserveRejectedWhileWarmingUp() {
        val c = VaultProviderCache()
        c.beginLoad(uuid, now)
        c.onInitialLoad(uuid, 1000, 1, now + 3000, now)
        assertFalse(c.reserveDecrease(uuid, op("a", 100)))
    }

    @Test
    @DisplayName("READY で残高十分なら減算予約が成功し availableBalance が減る")
    fun reserveSucceeds() {
        val c = readyCache(1000)
        assertTrue(c.reserveDecrease(uuid, op("a", 700)))
        val e = c.get(uuid)!!
        assertEquals(300, e.availableBalance())
        assertEquals(300, e.visibleBalance())
        assertEquals(-700, e.pendingDelta())
    }

    @Test
    @DisplayName("二重引き落とし防止: 合計が残高を超える 2 件目の予約は失敗する")
    fun doubleSpendPrevented() {
        val c = readyCache(100_000)
        assertTrue(c.reserveDecrease(uuid, op("pay", 70_000)))   // /pay 70,000
        assertFalse(c.reserveDecrease(uuid, op("shop", 70_000))) // 外部ショップ 70,000 -> 不足で失敗
        assertEquals(30_000, c.get(uuid)!!.availableBalance())
    }

    @Test
    @DisplayName("同一 operationId の二重予約は失敗する")
    fun duplicateOperationIdRejected() {
        val c = readyCache(1000)
        assertTrue(c.reserveDecrease(uuid, op("a", 100)))
        assertFalse(c.reserveDecrease(uuid, op("a", 100)))
        assertEquals(900, c.get(uuid)!!.availableBalance())
    }

    @Test
    @DisplayName("confirm で予約を外し、新しい version の権威残高を反映する")
    fun confirmAppliesAuthoritative() {
        val c = readyCache(1000, version = 1)
        c.reserveDecrease(uuid, op("a", 400))
        // Service が確定: 残高 600, version 2
        c.confirm(uuid, "a", 600, 2)
        val e = c.get(uuid)!!
        assertEquals(600, e.confirmedBalance)
        assertEquals(2, e.confirmedVersion)
        assertEquals(0, e.pendingDelta())
        assertEquals(600, e.availableBalance())
    }

    @Test
    @DisplayName("cancelPending で予約を取り消すと availableBalance が戻る")
    fun cancelRestores() {
        val c = readyCache(1000)
        c.reserveDecrease(uuid, op("a", 400))
        assertEquals(600, c.get(uuid)!!.availableBalance())
        c.cancelPending(uuid, "a")
        assertEquals(1000, c.get(uuid)!!.availableBalance())
    }

    @Test
    @DisplayName("入金確定(予約なし)は applyAuthoritative/confirm で version が新しいときだけ反映")
    fun depositConfirmIncreasesOnlyOnNewerVersion() {
        val c = readyCache(1000, version = 5)
        // 古い version の入金 push は捨てる
        c.applyAuthoritative(uuid, 9999, 4, now)
        assertEquals(1000, c.get(uuid)!!.confirmedBalance)
        // 新しい version は反映
        c.applyAuthoritative(uuid, 1500, 6, now)
        assertEquals(1500, c.get(uuid)!!.confirmedBalance)
        assertEquals(6, c.get(uuid)!!.confirmedVersion)
    }

    @Test
    @DisplayName("未確定の入金は availableBalance に含めない(正の pendingDelta を作らない)")
    fun noPositivePendingDelta() {
        val c = readyCache(1000)
        // 減算予約のみが pendingDelta に効く。入金予約という概念は無い。
        c.reserveDecrease(uuid, op("a", 200))
        assertTrue(c.get(uuid)!!.pendingDelta() <= 0)
        assertEquals(800, c.get(uuid)!!.availableBalance())
    }

    @Test
    @DisplayName("未ロードは get で null")
    fun unloaded() {
        val c = VaultProviderCache()
        assertNull(c.get(uuid))
    }
}
