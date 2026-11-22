package red.man10.man10bank.service

import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.PlayersApiClient
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.util.errorMessage
import red.man10.man10bank.util.runOnMainThread
import java.util.UUID

/**
 * プレイヤー名から UUID を解決する共通サービス（/mpay の送金先、/bankop の対象指定などで使う）。
 * 解決順: オンライン → サーバーのユーザーキャッシュ（usercache.json） → 銀行サーバーの名簿（ログイン時登録・鯖群全体で共有）。
 * - 認証サーバー(Mojang)には問い合わせない。名簿に無い（この鯖群にログインしたことがない）名前や、
 *   銀行サーバーに接続できない場合は失敗として返す。
 * - Bukkit API を使うローカル解決はメインスレッドで行う（コルーチンから呼んでもよい）。
 */
class PlayerLookupService(
    private val plugin: Man10Bank,
    private val playersApi: PlayersApiClient,
) {
    data class ResolvedPlayer(val uuid: UUID, val name: String)

    /** オンラインプレイヤー、またはサーバーのユーザーキャッシュから解決する（ネットワークアクセスなし）。メインスレッドで呼ぶこと。 */
    fun resolveLocally(name: String): ResolvedPlayer? {
        plugin.server.getPlayerExact(name)?.let { return ResolvedPlayer(it.uniqueId, it.name) }
        plugin.server.getOfflinePlayerIfCached(name)?.let { return ResolvedPlayer(it.uniqueId, it.name ?: name) }
        return null
    }

    /**
     * ローカルで解決できなければ銀行サーバーの名簿へ問い合わせる。
     * - 未登録なら ApiHttpException(404, code=PlayerNotFound)、通信失敗ならその例外が失敗として返る。
     */
    suspend fun resolve(name: String): Result<ResolvedPlayer> {
        plugin.runOnMainThread { resolveLocally(name) }?.let { return Result.success(it) }
        return playersApi.findByName(name).mapCatching { ResolvedPlayer(UUID.fromString(it.uuid), it.name) }
    }

    /**
     * 解決失敗の理由を操作者向けの文言にする。
     * @param action 「〜できます」に入る動詞（例: "送金"、"指定"）。
     */
    fun failureMessage(name: String, result: Result<*>, action: String = "指定"): String {
        val cause = result.exceptionOrNull()
        val code = (cause as? ApiHttpException)?.code
        return when (code) {
            "PlayerNotFound" ->
                "プレイヤー「$name」が見つかりません。このサーバー群にログインしたことがあるプレイヤーにのみ${action}できます。"
            // 名簿の検索は名前の長さ（16文字以内）だけを検証する。一時的な障害ではないので再試行を促さない
            "ValidationError" -> "プレイヤー名「$name」が正しくありません。16文字以内のプレイヤー名を指定してください。"
            else -> "プレイヤー「$name」を確認できませんでした: ${result.errorMessage()} しばらくしてから再度お試しください。"
        }
    }
}
