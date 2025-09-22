package red.man10.man10bank.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日付フォーマットユーティリティ。
 */
object DateFormats {
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * ISO8601文字列をローカルタイムゾーンの「yyyy-MM-dd HH:mm:ss」に整形。
     * パースに失敗した場合は元文字列を返す。
     */
    fun toDateTime(iso8601: String, zone: ZoneId = ZoneId.systemDefault()): String {
        return try {
            OffsetDateTime.parse(iso8601).atZoneSameInstant(zone).format(dateTimeFormatter)
        } catch (_: Exception) {
            try {
                Instant.parse(iso8601).atZone(zone).format(dateTimeFormatter)
            } catch (_: Exception) {
                iso8601.replace("T", " ")
            }
        }
    }

    fun toDate(iso8601: String, zone: ZoneId = ZoneId.systemDefault()): String {
        return try {
            OffsetDateTime.parse(iso8601).atZoneSameInstant(zone).format(dateFormatter)
        } catch (_: Exception) {
            try {
                Instant.parse(iso8601).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            } catch (_: Exception) {
                iso8601.replace("T", " ")
            }
        }
    }

    /**
     * Instantをローカルタイムゾーンの「yyyy-MM-dd HH:mm:ss」に整形。
     */
    fun fromInstant(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        instant.atZone(zone).format(dateTimeFormatter)
}

