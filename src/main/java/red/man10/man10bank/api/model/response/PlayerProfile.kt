package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

/** プレイヤー名簿のレスポンス（`/api/Players`）。 */
@Serializable
data class PlayerProfile(
    val uuid: String,
    val name: String,
)
