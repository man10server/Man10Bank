package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.PlayersApiClient
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * /mpay <player> <amount>
 * - all はなし
 * - 二重実行で確認（30秒以内）
 * - サーバー側の transfer（単一トランザクション）を利用
 * - 送金先はオフラインでも可
 * - note/displayNote は暫定メッセージ
 *
 * 送金先 UUID の解決:
 * - オンライン → サーバーのユーザーキャッシュ → 銀行サーバーの名簿（ログイン時に登録。鯖群全体で共有）の順で解決する。
 * - 認証サーバー(Mojang)には問い合わせない。名簿に無い（この鯖群にログインしたことがない）名前や、
 *   銀行サーバーに接続できない場合は送金を中止する。
 *   旧実装の getOfflinePlayer(name) はメインスレッドで認証サーバーへブロッキング問い合わせを行い、
 *   取得失敗時にオフライン用の偽UUIDを返すため、存在しない口座へ送金して消失する問題があった。
 * - 確認（2回目）では初回に解決した UUID をそのまま使い、再解決で別の UUID に化けないようにする。
 */
class PayCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val bankService: BankService,
    private val playersApi: PlayersApiClient,
) : BaseCommand(
    allowPlayer = true,
    allowConsole = false,
    allowGeneralUser = true,
    requiredPermission = "man10bank.pay",
) {

    companion object {
        private const val CONFIRM_WINDOW_MS = 30_000L
        // 確認待ちMapは複数スレッド（コマンド/コルーチン）から参照されるためスレッドセーフにする（DESIGN 3.5）。
        private val confirmations: MutableMap<UUID, Pending> = ConcurrentHashMap()
    }

    private data class Pending(
        val target: UUID,
        val targetName: String,
        val amount: Double,
        val expiresAt: Long,
    ) {
        fun isExpired(now: Long): Boolean = now > expiresAt
        fun matches(targetName: String, amount: Double): Boolean =
            this.targetName.equals(targetName, ignoreCase = true) && this.amount == amount
    }

    private data class ResolvedTarget(val uuid: UUID, val name: String)

    override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
        sender as Player
        if (args.size != 2) {
            Messages.warn(sender, "使い方: /mpay <player> <amount>")
            return true
        }

        val targetName = args[0]
        val amount = args[1].toDoubleOrNull() ?: -1.0
        if (amount <= 0.0) {
            Messages.error(sender, "金額が不正です。正の数を指定してください。")
            return true
        }

        val confirmKey = sender.uniqueId
        val now = System.currentTimeMillis()
        val pending = confirmations[confirmKey]
        if (pending != null && !pending.isExpired(now) && pending.matches(targetName, amount)) {
            // 確認済み: 一度で削除してから実処理へ。UUID は初回に解決したものを使う。
            confirmations.remove(confirmKey)
            scope.launch { bankService.transfer(sender, pending.target, pending.targetName, amount) }
            return true
        }

        // 初回: 送金先 UUID を解決してから確認を求める。
        // オンライン／キャッシュ済みならメインスレッドで即座に解決でき、未知の名前のみ銀行サーバーの名簿へ非同期で問い合わせる。
        val cached = resolveTargetLocally(targetName)
        if (cached != null) {
            askConfirmation(sender, cached, amount)
            return true
        }
        scope.launch {
            val resolved = resolveTargetRemotely(targetName)
            val target = resolved.getOrNull()
            if (target == null) {
                val cause = resolved.exceptionOrNull()
                val message = if (cause is ApiHttpException && cause.code == "PlayerNotFound") {
                    "プレイヤー「$targetName」が見つかりません。このサーバー群にログインしたことがあるプレイヤーにのみ送金できます。"
                } else {
                    "プレイヤー「$targetName」を確認できませんでした: ${resolved.errorMessage()} しばらくしてから再度お試しください。"
                }
                Messages.error(plugin, sender, message)
                return@launch
            }
            askConfirmation(sender, target, amount)
        }
        return true
    }

    /** 確認待ちに登録し、同じコマンドの再実行を促す。非メインスレッドからも呼ばれる。 */
    private fun askConfirmation(sender: Player, target: ResolvedTarget, amount: Double) {
        if (sender.uniqueId == target.uuid && !sender.isOp) {
            Messages.error(plugin, sender, "自分自身へは送金できません。")
            return
        }
        confirmations[sender.uniqueId] = Pending(target.uuid, target.name, amount, System.currentTimeMillis() + CONFIRM_WINDOW_MS)
        Messages.sendMultiline(plugin, sender, """
            §l以下の内容で送金します
            §7- 送金相手: §e§l${target.name} 
            §7- 送金金額: ${BalanceFormats.coloredYen(amount)}
            §l§nもう一度同じコマンドを${CONFIRM_WINDOW_MS / 1000}秒以内に実行して確認してください
        """.trimIndent())
    }

    /** オンラインプレイヤー、またはサーバーのユーザーキャッシュから解決する（ネットワークアクセスなし・メインスレッド用）。 */
    private fun resolveTargetLocally(name: String): ResolvedTarget? {
        plugin.server.getPlayerExact(name)?.let { return ResolvedTarget(it.uniqueId, it.name) }
        plugin.server.getOfflinePlayerIfCached(name)?.let { return ResolvedTarget(it.uniqueId, it.name ?: name) }
        return null
    }

    /**
     * 銀行サーバーの名簿（ログイン時に登録される）から名前で解決する。
     * - この鯖群にログインしたことがあるプレイヤーなら、他のバックエンドにしかログインしていなくても解決できる。
     * - 未登録なら ApiHttpException(404, PlayerNotFound)、通信失敗ならその例外が失敗として返る。いずれも送金は中止する。
     */
    private suspend fun resolveTargetRemotely(name: String): Result<ResolvedTarget> =
        playersApi.findByName(name).mapCatching { ResolvedTarget(UUID.fromString(it.uuid), it.name) }
}
