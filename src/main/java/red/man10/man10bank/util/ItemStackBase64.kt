package red.man10.man10bank.util

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * ItemStack <-> Base64 変換ユーティリティ。
 * - ItemStack.serializeAsBytes を使用してBase64化する。
 * - 旧形式（YAMLシリアライズ）は oldDecode で復元可能。
 */
object ItemStackBase64 {

    /**
     * ItemStack を Base64 文字列へエンコードする。
     * - nullやAIR相当の判定は呼び出し側で行う想定
     */
    fun encode(item: ItemStack): String {
        val bytes = item.serializeAsBytes()
        return Base64Coder.encodeLines(bytes)
    }

    /**
     * Base64 文字列から ItemStack へデコードする。
     * - 失敗時は旧形式のデコードを試み、最終的に例外を投げる
     */
    fun decode(base64: String): ItemStack {
        return try {
            val bytes = Base64Coder.decodeLines(base64)
            ItemStack.deserializeBytes(bytes)
        } catch (newError: Exception) {
            try {
                oldDecode(base64)
            } catch (oldError: Exception) {
                oldError.addSuppressed(newError)
                throw oldError
            }
        }
    }

    /**
     * 旧形式のBase64文字列をItemStackへデコードする。
     * - YAMLへシリアライズしたものをBase64化した形式に対応
     */
    fun oldDecode(base64: String): ItemStack {
        val bytes = Base64.getDecoder().decode(base64)
        val yamlText = String(bytes, StandardCharsets.UTF_8)
        val yaml = YamlConfiguration()
        yaml.loadFromString(yamlText)
        return yaml.getItemStack("i")
            ?: throw IllegalArgumentException("旧形式のItemStackデコードに失敗しました: i が存在しません")
    }

    /**
     * ItemStackのリストを Base64 文字列へエンコードする。
     * - ItemStack.serializeAsBytes を連結し、Base64化する
     */
    fun encodeItems(items: List<ItemStack>): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(items.size)
            for (item in items) {
                val itemBytes = item.serializeAsBytes()
                data.writeInt(itemBytes.size)
                data.write(itemBytes)
            }
        }
        return Base64Coder.encodeLines(output.toByteArray())
    }

    /**
     * Base64化されたItemStackリストを復元する。
     * - 失敗時は旧形式のデコードを試み、最終的に例外を投げる
     */
    fun decodeItems(base64: String): List<ItemStack> {
        return try {
            val bytes = Base64Coder.decodeLines(base64)
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val count = data.readInt()
                if (count < 0) {
                    throw IllegalArgumentException("ItemStackリストの件数が不正です: count=$count")
                }
                val items = ArrayList<ItemStack>(count)
                repeat(count) { index ->
                    val itemSize = data.readInt()
                    if (itemSize < 0) {
                        throw IllegalArgumentException("ItemStackサイズが不正です: index=$index size=$itemSize")
                    }
                    val itemBytes = ByteArray(itemSize)
                    data.readFully(itemBytes)
                    items.add(ItemStack.deserializeBytes(itemBytes))
                }
                items
            }
        } catch (newError: Exception) {
            try {
                oldDecodeItems(base64)
            } catch (oldError: Exception) {
                oldError.addSuppressed(newError)
                throw oldError
            }
        }
    }

    /**
     * 旧形式のBase64文字列をItemStackリストへデコードする。
     * - YAMLへシリアライズしたリストをBase64化した形式に対応
     */
    fun oldDecodeItems(base64: String): List<ItemStack> {
        val bytes = Base64.getDecoder().decode(base64)
        val yamlText = String(bytes, StandardCharsets.UTF_8)
        val yaml = YamlConfiguration()
        yaml.loadFromString(yamlText)
        val raw = yaml.getList("l")
            ?: throw IllegalArgumentException("旧形式のItemStackリストデコードに失敗しました: l が存在しません")
        return raw.mapIndexed { index, entry ->
            when (entry) {
                is ItemStack -> entry
                is String -> decode(entry)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    ItemStack.deserialize(entry as Map<String, Any>)
                }
                else -> throw IllegalArgumentException("旧形式のItemStack要素が不正です: index=$index type=${entry?.javaClass?.name}")
            }
        }
    }
}
