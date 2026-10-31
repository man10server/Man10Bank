package red.man10.man10bank.api.model.request

import kotlinx.serialization.Serializable

/** プレイヤー名簿への登録（`POST /api/Players`）。ログイン時に UUID と現在の Minecraft ID を送る。 */
@Serializable
data class PlayerRegisterRequest(
    val uuid: String,
    val name: String,
)
