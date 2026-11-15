package red.man10.man10bank.command.op.sub.adjust

/** /bankop give|take|set の操作種別。 */
enum class AdjustVerb(
    val key: String,
    /** メッセージ用の動詞（「銀行残高を加算しました」など）。 */
    val labelJa: String,
    /** usage 用の説明。 */
    val description: String,
) {
    GIVE("give", "加算", "指定口座の残高に金額を加算"),
    TAKE("take", "減算", "指定口座の残高から金額を減算"),
    SET("set", "設定", "指定口座の残高を金額に設定");

    /**
     * 現在値と指定額から変更後の値を求める。
     * - 変更後が 0 未満になる場合、[floorAtZero] なら 0 で止め、そうでなければ残高不足として失敗を返す。
     */
    fun plan(current: Double, amount: Double, floorAtZero: Boolean): Result<AdjustPlan> {
        val after = when (this) {
            GIVE -> current + amount
            TAKE -> current - amount
            SET -> amount
        }
        if (after < 0.0) {
            if (!floorAtZero) {
                return Result.failure(
                    IllegalArgumentException("残高不足のため${labelJa}できません（現在: ${current.toLong()}円、指定: ${amount.toLong()}円）")
                )
            }
            return Result.success(AdjustPlan(before = current, after = 0.0))
        }
        return Result.success(AdjustPlan(before = current, after = after))
    }

    companion object {
        fun fromKey(key: String): AdjustVerb? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** 変更前後の値。差分は after - before。 */
data class AdjustPlan(val before: Double, val after: Double) {
    val delta: Double get() = after - before
}
