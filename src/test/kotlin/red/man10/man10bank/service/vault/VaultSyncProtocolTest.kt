package red.man10.man10bank.service.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VaultSyncProtocol のテスト（メッセージ符号化）")
class VaultSyncProtocolTest {

    @Test
    @DisplayName("balance イベントを type/uuid/balance/version でデコードできる")
    fun decodeBalanceEvent() {
        val json = """
            {"type":"balance","uuid":"0a1b2c3d-0000-0000-0000-000000000001",
             "balance":123450,"version":42,"cause":"DEPOSIT","originServer":"lobby","ts":"2026-06-16T10:00:00Z"}
        """.trimIndent()
        val event = VaultSyncProtocol.decode(json)
        requireNotNull(event)
        assertEquals("balance", event.type)
        assertEquals("0a1b2c3d-0000-0000-0000-000000000001", event.uuid)
        assertEquals(123450.0, event.balance)
        assertEquals(42L, event.version)
        assertEquals("DEPOSIT", event.cause)
    }

    @Test
    @DisplayName("未知フィールドは無視し、不正JSONは null を返す")
    fun decodeLenient() {
        assertEquals("ping", VaultSyncProtocol.decode("""{"type":"ping","extra":1}""")?.type)
        assertNull(VaultSyncProtocol.decode("これはJSONではない"))
    }

    @Test
    @DisplayName("presence(join/quit) は type/action/uuid/server を含む")
    fun presenceJson() {
        val json = VaultSyncProtocol.presence("join", "0a1b2c3d-0000-0000-0000-000000000001", "survival")
        val decoded = VaultSyncProtocol.decode(json)
        requireNotNull(decoded)
        assertEquals("presence", decoded.type)
        assertTrue(json.contains("\"action\":\"join\""))
        assertTrue(json.contains("\"server\":\"survival\""))
        assertTrue(json.contains("0a1b2c3d-0000-0000-0000-000000000001"))
    }

    @Test
    @DisplayName("pong はハートビート応答JSON")
    fun pong() {
        assertEquals("{\"type\":\"pong\"}", VaultSyncProtocol.pong())
    }
}
