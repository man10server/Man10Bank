package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * 資産スナップショットレスポンス（DESIGN 1.4）。
 * - `POST /api/Estate/{uuid}/snapshot` が返す `{ "updated": bool }` 形式に対応する。
 * - 資産情報に変更がなかった場合は updated=false。
 */
@Serializable
data class SnapshotResponse(
    val updated: Boolean,
)
