package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.http.encodeURLPathPart
import red.man10.man10bank.api.model.request.PlayerRegisterRequest
import red.man10.man10bank.api.model.response.PlayerProfile
import java.util.UUID

/**
 * /api/Players 用のAPIクライアント（プレイヤー名簿）。
 * - ログイン時に UUID と Minecraft ID を登録し、サーバー側の名前解決に使ってもらう（改名はここで追従する）。
 * - 名前から UUID を解決する（/mpay の送金先）。認証サーバー(Mojang)には問い合わせない。
 */
class PlayersApiClient(private val client: HttpClient) {

    /** 名簿へ登録（既存なら名前を更新）。登録後のプロフィールが返ります。 */
    suspend fun register(body: PlayerRegisterRequest): Result<PlayerProfile> =
        client.postJson("/api/Players", body)

    /** UUID から名簿を引く。未登録なら ApiHttpException(404, code=PlayerNotFound)。 */
    suspend fun findByUuid(uuid: UUID): Result<PlayerProfile> =
        client.getJson("/api/Players/${uuid}")

    /**
     * 名前から名簿を引く（大文字小文字は区別しない）。
     * - この鯖群にログインしたことがある名前のみ解決できる。未登録なら ApiHttpException(404, code=PlayerNotFound)。
     */
    suspend fun findByName(name: String): Result<PlayerProfile> =
        client.getJson("/api/Players/by-name/${name.encodeURLPathPart()}")
}
