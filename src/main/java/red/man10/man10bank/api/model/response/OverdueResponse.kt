package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/**
 * サーバーローンの延滞状態レスポンス。
 * - `GET /api/ServerLoan/{uuid}/overdue` が返す `{ "overdue": boolean }` 形式に対応する。
 * - overdue は「残債があり、直近の定期返済が失敗のまま」のとき true。
 */
@Serializable
data class OverdueResponse(
    val overdue: Boolean,
)
