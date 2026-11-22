package red.man10.man10bank.service

import org.bukkit.inventory.ItemStack
import red.man10.man10bank.util.ItemStackBase64
import java.util.UUID

/**
 * アイテムを台帳の形式（ItemStackBase64.encodeItems）にして記録し、要手動対応ログに添える記録IDを返す。
 * 記録できなかった場合は「記録失敗」を返す（その場合はログに出すアイテムデータが唯一の控えになる）。
 * [encoded] には、encodeItems 済みの文字列があれば渡す（ローン作成結果不明ではサーバーへ送った文字列をそのまま残し、
 * 作成済みのローンと照合できるようにする）。
 */
fun RecoveryLedger.recordItems(
    type: RecoveryLedger.Type,
    owner: UUID,
    items: List<ItemStack>,
    detail: String,
    loanId: Int? = null,
    lender: UUID? = null,
    encoded: String? = null,
): String {
    val base64 = encoded
        ?: runCatching { ItemStackBase64.encodeItems(items) }.getOrNull()
        ?: return "記録失敗(エンコード失敗)"
    return record(type, owner, base64, items.size, detail, loanId, lender) ?: "記録失敗"
}
