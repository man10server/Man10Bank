package red.man10.man10bank.util

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * ItemStack <-> Base64 変換ユーティリティ。
 * - Javaシリアライズ（BukkitObjectOutputStream等）は非推奨のため使用しない。
 * - BukkitのYAMLシリアライズ（ConfigurationSerializable）を用いて文字列化し、Base64で安全に転送可能にする。
 */
object ItemStackBase64 {

    /**
     * ItemStack を Base64 文字列へエンコードする。
     * - YAMLへシリアライズ後、UTF-8バイト列をBase64化
     * - nullやAIR相当の判定は呼び出し側で行う想定
     */
    fun encode(item: ItemStack): String {
        val yaml = YamlConfiguration()
        yaml.set("i", item)
        val serialized = yaml.saveToString()
        val bytes = serialized.toByteArray(StandardCharsets.UTF_8)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Base64 文字列から ItemStack へデコードする。
     * - 失敗時は null を返す
     */
    fun decode(base64: String): ItemStack? = try {
        val bytes = Base64.getDecoder().decode(base64)
        val yamlText = String(bytes, StandardCharsets.UTF_8)
        val yaml = YamlConfiguration()
        yaml.loadFromString(yamlText)
        yaml.getItemStack("i")
    } catch (_: Exception) {
        null
    }
}
