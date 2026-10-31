package red.man10.man10bank.command.transaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.command.BaseCommand
import red.man10.man10bank.service.BankService
import red.man10.man10bank.util.BalanceFormats
import red.man10.man10bank.util.Messages
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
 * - オンライン → サーバーのユーザーキャッシュ → 認証サーバー(Mojang) の順で解決する。
 * - 認証サーバーへの問い合わせはブロッキングのため非同期で行い、取得できない場合は送金を中止する。
 *   旧実装の getOfflinePlayer(name) は取得失敗時にオフライン用の偽UUIDを返すため、
 *   存在しない口座へ送金して消失する／メインスレッドを数秒止める問題があった。
 * - 確認（2回目）では初回に解決した UUID をそのまま使い、再解決で別の UUID に化けないようにする。
 */
class PayCommand(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val bankService: BankService,
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
        // オンライン／キャッシュ済みならメインスレッドで即座に解決でき、未知の名前のみ認証サーバーへ非同期で問い合わせる。
        val cached = resolveTargetLocally(targetName)
        if (cached != null) {
            askConfirmation(sender, cached, amount)
            return true
        }
        scope.launch {
            val resolved = resolveTargetRemotely(targetName)
            if (resolved == null) {
                Messages.error(plugin, sender,
                    "プレイヤー「$targetName」の情報を取得できませんでした。名前が正しいか確認し、しばらくしてから再度お試しください。")
                return@launch
            }
            askConfirmation(sender, resolved, amount)
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
     * 認証サーバー(Mojang)へ問い合わせて解決する（ブロッキング I/O のため IO ディスパッチャで実行）。
     * - 取得できない（接続不可・存在しない名前）場合は null を返し、偽UUIDへの送金を防ぐ。
     * - オフラインモードのサーバーでは従来どおりオフライン用 UUID が得られる。
     */
    private suspend fun resolveTargetRemotely(name: String): ResolvedTarget? = withContext(Dispatchers.IO) {
        val profile = plugin.server.createProfile(name)
        val completed = runCatching { profile.complete(false) }.getOrDefault(false)
        val id = profile.id
        if (!completed || id == null) {
            plugin.logger.warning("送金先の名前解決に失敗しました: name=$name 詳細=認証サーバーからUUIDを取得できなかったため送金を中止した")
            return@withContext null
        }
        ResolvedTarget(id, profile.name ?: name)
    }
}
