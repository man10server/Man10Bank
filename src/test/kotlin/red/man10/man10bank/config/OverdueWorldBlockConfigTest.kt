package red.man10.man10bank.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OverdueWorldBlockConfig のテスト")
class OverdueWorldBlockConfigTest {

    private fun read(yaml: String): OverdueWorldBlockConfig =
        OverdueWorldBlockConfig.from(YamlConfiguration().apply { loadFromString(yaml) })

    @Test
    @DisplayName("設定が無い場合は無効で、文言は既定値になる")
    fun disabledWhenSectionMissing() {
        val config = read("features:\n  atm: true\n")
        assertFalse(config.enabled)
        assertFalse(config.isBlocked("world"))
        assertEquals(OverdueWorldBlockConfig.DEFAULT_MESSAGE, config.message)
    }

    @Test
    @DisplayName("ワールド名は前後の空白を除き、大文字小文字を区別せずに判定する")
    fun matchesWorldNamesIgnoringCase() {
        val config = read(
            """
            overdueWorldBlock:
              worlds: [" Casino ", "shop_world", ""]
            """.trimIndent()
        )
        assertTrue(config.enabled)
        assertEquals(setOf("casino", "shop_world"), config.worlds)
        assertTrue(config.isBlocked("casino"))
        assertTrue(config.isBlocked("CASINO"))
        assertTrue(config.isBlocked("Shop_World"))
        assertFalse(config.isBlocked("world"))
    }

    @Test
    @DisplayName("ワールドの追加と削除は大文字小文字を区別せず、元の設定は変えない")
    fun addsAndRemovesWorlds() {
        val empty = read("features:\n  atm: true\n")

        val added = empty.withWorld(" Casino ")
        assertTrue(added.enabled)
        assertTrue(added.isBlocked("casino"))
        assertEquals(setOf("casino"), added.withWorld("CASINO").worlds)
        assertFalse(empty.enabled)

        val removed = added.withoutWorld("CASINO")
        assertFalse(removed.enabled)
        assertEquals(added.message, removed.message)
    }

    @Test
    @DisplayName("文言は設定値を使い、空の場合は既定値に戻す")
    fun readsMessage() {
        val custom = read(
            """
            overdueWorldBlock:
              worlds: [casino]
              message: "§c返済してから来てください"
            """.trimIndent()
        )
        assertEquals("§c返済してから来てください", custom.message)

        val blank = read(
            """
            overdueWorldBlock:
              worlds: [casino]
              message: "  "
            """.trimIndent()
        )
        assertEquals(OverdueWorldBlockConfig.DEFAULT_MESSAGE, blank.message)
    }
}
