package red.man10.man10bank.util

import java.math.BigDecimal
import java.math.RoundingMode

object BigDecimalConverter {

    fun fromDouble(value: Double): BigDecimal {
        return BigDecimal.valueOf(value).setScale(0, RoundingMode.DOWN)
    }

    fun toDouble(value: BigDecimal): Double {
        return value.setScale(0, RoundingMode.DOWN).toDouble()
    }

    fun parseOrNull(text: String): BigDecimal? = try {
        BigDecimal(text)
    } catch (_: Exception) {
        null
    }

}
