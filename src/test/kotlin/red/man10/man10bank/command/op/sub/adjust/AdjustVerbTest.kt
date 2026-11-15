package red.man10.man10bank.command.op.sub.adjust

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * /bankop give|take|set の金額計算のテスト。
 * - give は加算、take は減算、set は指定額。
 * - 0 未満になる減算は、floorAtZero なら 0 で止まり（サーバーローン）、そうでなければ残高不足として失敗する（銀行・電子マネー）。
 */
@DisplayName("AdjustVerb のテスト")
class AdjustVerbTest {

    @Test
    @DisplayName("giveは現在値に加算し、差分は指定額になる")
    fun giveAddsAmount() {
        val plan = AdjustVerb.GIVE.plan(current = 1000.0, amount = 250.0, floorAtZero = false).getOrThrow()
        assertEquals(1000.0, plan.before)
        assertEquals(1250.0, plan.after)
        assertEquals(250.0, plan.delta)
    }

    @Test
    @DisplayName("takeは現在値から減算し、差分は負になる")
    fun takeSubtractsAmount() {
        val plan = AdjustVerb.TAKE.plan(current = 1000.0, amount = 300.0, floorAtZero = false).getOrThrow()
        assertEquals(700.0, plan.after)
        assertEquals(-300.0, plan.delta)
    }

    @Test
    @DisplayName("setは指定額にし、差分は指定額と現在値の差になる")
    fun setUsesAmountAsIs() {
        val up = AdjustVerb.SET.plan(current = 1000.0, amount = 1500.0, floorAtZero = false).getOrThrow()
        assertEquals(1500.0, up.after)
        assertEquals(500.0, up.delta)
        val down = AdjustVerb.SET.plan(current = 1000.0, amount = 0.0, floorAtZero = false).getOrThrow()
        assertEquals(0.0, down.after)
        assertEquals(-1000.0, down.delta)
    }

    @Test
    @DisplayName("残高を超えるtakeはfloorAtZero=falseなら残高不足として失敗する(銀行・電子マネー)")
    fun takeBeyondBalanceFailsWithoutFloor() {
        val result = AdjustVerb.TAKE.plan(current = 100.0, amount = 500.0, floorAtZero = false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("残高不足"))
    }

    @Test
    @DisplayName("残高を超えるtakeはfloorAtZero=trueなら0で止まる(サーバーローン=完済扱い)")
    fun takeBeyondBalanceFloorsAtZero() {
        val plan = AdjustVerb.TAKE.plan(current = 100.0, amount = 500.0, floorAtZero = true).getOrThrow()
        assertEquals(0.0, plan.after)
        assertEquals(-100.0, plan.delta)
    }

    @Test
    @DisplayName("現在値と同じsetや、0円に対するtake(floor)は差分0になる")
    fun noChangeYieldsZeroDelta() {
        assertEquals(0.0, AdjustVerb.SET.plan(1000.0, 1000.0, false).getOrThrow().delta)
        assertEquals(0.0, AdjustVerb.TAKE.plan(0.0, 50.0, true).getOrThrow().delta)
    }

    @Test
    @DisplayName("fromKeyは大文字小文字を区別せず解決し、未知のキーはnull")
    fun fromKeyResolvesIgnoringCase() {
        assertEquals(AdjustVerb.GIVE, AdjustVerb.fromKey("give"))
        assertEquals(AdjustVerb.TAKE, AdjustVerb.fromKey("TAKE"))
        assertEquals(AdjustVerb.SET, AdjustVerb.fromKey("Set"))
        assertNull(AdjustVerb.fromKey("edit"))
    }
}
