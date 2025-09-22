package red.man10.man10bank.util

import java.util.Locale

/**
 * 数値フォーマットユーティリティ。
 * - 金額: 小数は表示せず、3桁カンマ区切り（整数）
 */
object BalanceFormats {
    /** Double入力を色付きで整数表示に（小数は切り捨て）。 */
    fun coloredYen(value: Double): String = "§e§l${amount(value)}円§r"

    /** Double入力を整数表示に（小数は切り捨て）。 */
    fun amount(value: Double): String = String.format(Locale.JAPAN, "%,d", value.toLong())
}

