package red.man10.man10bank.util

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * ItemStack <-> Base64 変換ユーティリティ。
 */
object ItemStackBase64 {

    /**
     * ItemStack を Base64 文字列へエンコードする。
     * 失敗時は例外を送出する。
     */
    fun encode(item: ItemStack): String {
        val bos = ByteArrayOutputStream()
        BukkitObjectOutputStream(bos).use { oos ->
            oos.writeObject(item)
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    /**
     * Base64 文字列から ItemStack へデコードする。
     * パースに失敗した場合は null を返す。
     */
    fun decode(base64: String): ItemStack? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
                @Suppress("UNCHECKED_CAST")
                ois.readObject() as? ItemStack
            }
        } catch (_: Exception) {
            null
        }
    }
}

