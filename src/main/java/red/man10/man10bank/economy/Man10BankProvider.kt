package red.man10.man10bank.economy

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import net.milkbowl.vault.economy.EconomyResponse.ResponseType
import org.bukkit.OfflinePlayer
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.service.vault.VaultService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Man10Bank を Vault(Economy) Provider 化する同期互換レイヤ(設計書 §6)。
 *
 * - すべての Economy メソッドはメインスレッドで実行する。off-main から呼ばれた場合は
 *   Bukkit メインスレッドへ同期ディスパッチし、結果が返るまで呼び出し元スレッドをブロックする。
 *   HTTP/DB の完了は待たず、Provider キャッシュと送信待ちキューの同期処理だけを待つ。
 * - 残高は内部で整数円(Long)。Vault 境界でのみ Double に変換する。fractionalDigits()=0。
 * - 単一通貨・全 world 共通。world 引数付き overload は world 名を無視して通常版へ委譲する。
 * - String / OfflinePlayer 指定は「自サーバーで現在オンライン」の場合だけ解決する。オフライン UUID は生成しない。
 */
class Man10BankProvider(
    private val plugin: Man10Bank,
    private val service: VaultService,
) : Economy {

    // ======================= メタ情報 =======================

    override fun isEnabled(): Boolean = service.isProviderEnabled()
    override fun getName(): String = "Man10Bank"
    override fun hasBankSupport(): Boolean = false
    override fun fractionalDigits(): Int = 0
    override fun currencyNamePlural(): String = "円"
    override fun currencyNameSingular(): String = "円"

    override fun format(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return "0円"
        // 小数部を 0 方向へ切り捨て、3 桁カンマと円を付ける。例: 1234.9 -> "1,234円"、-1234.9 -> "-1,234円"。
        val truncated = amount.toLong()
        return "%,d円".format(truncated)
    }

    // ======================= アカウント =======================

    override fun hasAccount(player: OfflinePlayer): Boolean =
        onMain(false) {
            val uuid = onlineUuid(player) ?: return@onMain false
            service.providerHasAccount(uuid)
        }

    override fun hasAccount(player: OfflinePlayer, worldName: String?): Boolean = hasAccount(player)

    override fun hasAccount(playerName: String?): Boolean =
        onMain(false) {
            val uuid = onlineUuid(playerName) ?: return@onMain false
            service.providerHasAccount(uuid)
        }

    override fun hasAccount(playerName: String?, worldName: String?): Boolean = hasAccount(playerName)

    override fun createPlayerAccount(player: OfflinePlayer): Boolean =
        onMain(false) {
            val uuid = onlineUuid(player) ?: return@onMain false
            service.providerCreateAccount(uuid)
        }

    override fun createPlayerAccount(player: OfflinePlayer, worldName: String?): Boolean = createPlayerAccount(player)

    override fun createPlayerAccount(playerName: String?): Boolean =
        onMain(false) {
            val uuid = onlineUuid(playerName) ?: return@onMain false
            service.providerCreateAccount(uuid)
        }

    override fun createPlayerAccount(playerName: String?, worldName: String?): Boolean = createPlayerAccount(playerName)

    // ======================= 残高参照 =======================

    override fun getBalance(player: OfflinePlayer): Double =
        onMain(0.0) {
            if (!service.isProviderEnabled()) return@onMain 0.0
            val uuid = onlineUuid(player) ?: return@onMain 0.0
            service.providerGetVisibleBalance(uuid).toDouble()
        }

    override fun getBalance(player: OfflinePlayer, world: String?): Double = getBalance(player)

    override fun getBalance(playerName: String?): Double =
        onMain(0.0) {
            if (!service.isProviderEnabled()) return@onMain 0.0
            val uuid = onlineUuid(playerName) ?: return@onMain 0.0
            service.providerGetVisibleBalance(uuid).toDouble()
        }

    override fun getBalance(playerName: String?, world: String?): Double = getBalance(playerName)

    override fun has(player: OfflinePlayer, amount: Double): Boolean =
        onMain(false) {
            if (!service.isProviderEnabled()) return@onMain false
            val uuid = onlineUuid(player) ?: return@onMain false
            val normalized = normalize(amount) ?: return@onMain false
            service.providerHas(uuid, normalized)
        }

    override fun has(player: OfflinePlayer, worldName: String?, amount: Double): Boolean = has(player, amount)

    override fun has(playerName: String?, amount: Double): Boolean =
        onMain(false) {
            if (!service.isProviderEnabled()) return@onMain false
            val uuid = onlineUuid(playerName) ?: return@onMain false
            val normalized = normalize(amount) ?: return@onMain false
            service.providerHas(uuid, normalized)
        }

    override fun has(playerName: String?, worldName: String?, amount: Double): Boolean = has(playerName, amount)

    // ======================= 出金 =======================

    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        onMain(failure(0.0, "メインスレッドへのディスパッチに失敗しました")) {
            doWithdraw(onlineUuid(player), amount, player.name)
        }

    override fun withdrawPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    override fun withdrawPlayer(playerName: String?, amount: Double): EconomyResponse =
        onMain(failure(0.0, "メインスレッドへのディスパッチに失敗しました")) {
            doWithdraw(onlineUuid(playerName), amount, playerName)
        }

    override fun withdrawPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse =
        withdrawPlayer(playerName, amount)

    private fun doWithdraw(uuid: UUID?, amount: Double, who: String?): EconomyResponse {
        if (!service.isProviderEnabled()) return failure(0.0, "電子マネー機能は現在無効です")
        if (uuid == null) return failure(0.0, "対象プレイヤーがオンラインではありません")
        val normalized = normalize(amount) ?: return failure(0.0, "金額が不正です")
        val ok = service.providerWithdraw(uuid, normalized, callerPlugin(), "vault-withdraw:$who")
        val balance = service.providerGetVisibleBalance(uuid).toDouble()
        return if (ok) {
            EconomyResponse(normalized.toDouble(), balance, ResponseType.SUCCESS, null)
        } else {
            failure(balance, "残高が不足しているか、取引できない状態です")
        }
    }

    // ======================= 入金 =======================

    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        onMain(failure(0.0, "メインスレッドへのディスパッチに失敗しました")) {
            doDeposit(onlineUuid(player), amount, player.name)
        }

    override fun depositPlayer(player: OfflinePlayer, worldName: String?, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    override fun depositPlayer(playerName: String?, amount: Double): EconomyResponse =
        onMain(failure(0.0, "メインスレッドへのディスパッチに失敗しました")) {
            doDeposit(onlineUuid(playerName), amount, playerName)
        }

    override fun depositPlayer(playerName: String?, worldName: String?, amount: Double): EconomyResponse =
        depositPlayer(playerName, amount)

    private fun doDeposit(uuid: UUID?, amount: Double, who: String?): EconomyResponse {
        if (!service.isProviderEnabled()) return failure(0.0, "電子マネー機能は現在無効です")
        if (uuid == null) return failure(0.0, "対象プレイヤーがオンラインではありません")
        val normalized = normalize(amount) ?: return failure(0.0, "金額が不正です")
        val ok = service.providerDeposit(uuid, normalized, callerPlugin(), "vault-deposit:$who")
        // DB 確定までキャッシュ残高は増やさないため、balance は現在の visibleBalance を返す。
        val balance = service.providerGetVisibleBalance(uuid).toDouble()
        return if (ok) {
            EconomyResponse(normalized.toDouble(), balance, ResponseType.SUCCESS, null)
        } else {
            failure(balance, "現在電子マネーへの入金を受け付けられません")
        }
    }

    // ======================= Bank 系(未対応) =======================

    private fun notImplemented(): EconomyResponse =
        EconomyResponse(0.0, 0.0, ResponseType.NOT_IMPLEMENTED, "Man10Bank はVaultのBank機能をサポートしていません")

    override fun createBank(name: String?, player: String?): EconomyResponse = notImplemented()
    override fun createBank(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()
    override fun deleteBank(name: String?): EconomyResponse = notImplemented()
    override fun bankBalance(name: String?): EconomyResponse = notImplemented()
    override fun bankHas(name: String?, amount: Double): EconomyResponse = notImplemented()
    override fun bankWithdraw(name: String?, amount: Double): EconomyResponse = notImplemented()
    override fun bankDeposit(name: String?, amount: Double): EconomyResponse = notImplemented()
    override fun isBankOwner(name: String?, playerName: String?): EconomyResponse = notImplemented()
    override fun isBankOwner(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()
    override fun isBankMember(name: String?, playerName: String?): EconomyResponse = notImplemented()
    override fun isBankMember(name: String?, player: OfflinePlayer?): EconomyResponse = notImplemented()
    override fun getBanks(): MutableList<String> = mutableListOf()

    // ======================= ヘルパ =======================

    private fun failure(balance: Double, message: String) =
        EconomyResponse(0.0, balance, ResponseType.FAILURE, message)

    // 自サーバーで現在オンラインの OfflinePlayer のみ UUID を返す。
    private fun onlineUuid(player: OfflinePlayer?): UUID? {
        val uuid = player?.uniqueId ?: return null
        return if (plugin.server.getPlayer(uuid) != null) uuid else null
    }

    // Minecraft ID として扱い、現在オンラインのプレイヤーのみ解決する(オフライン UUID は生成しない)。
    private fun onlineUuid(name: String?): UUID? {
        if (name.isNullOrBlank()) return null
        return plugin.server.getPlayerExact(name)?.uniqueId
    }

    // 金額正規化: 有限かつ正、切り捨て後が 1 円以上なら Long を返す。それ以外は null(拒否)。
    private fun normalize(amount: Double): Long? {
        if (amount.isNaN() || amount.isInfinite()) return null
        if (amount <= 0.0) return null
        val truncated = amount.toLong()
        return if (truncated >= 1L) truncated else null
    }

    private fun callerPlugin(): String = "vault"

    // メインスレッドで block を実行する。off-main からはディスパッチして結果を待つ。
    //
    // タイムアウト時に「呼び出し元へ FAILURE(default)を返したのに、後でメインスレッドが block を実行して
    // 予約/キュー登録という副作用を起こす」乖離を防ぐ。これが起きると Vault 呼び出し元には失敗、実際は
    // DB 出金確定となり、補償有無で増殖/消失する(設計書 §5.2 不変条件3: SUCCESS ⟺ キュー登録)。
    //
    // claimed の CAS で「待機側の打ち切り」と「タスクの block 実行」を排他にする:
    // - タスク側が先に claim → block を実行し実結果を返す(待機側は実結果を待つ)。
    // - 待機側がタイムアウトで先に claim → タスクは block を実行せず、default を安全に返す。
    private fun <T> onMain(default: T, block: () -> T): T {
        if (plugin.server.isPrimaryThread) {
            return try { block() } catch (t: Throwable) { default }
        }
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val future = CompletableFuture<T>()
        try {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!claimed.compareAndSet(false, true)) return@Runnable // 待機側が打ち切り済み: 副作用を実行しない
                try { future.complete(block()) } catch (t: Throwable) { future.completeExceptionally(t) }
            })
        } catch (t: Throwable) {
            return default
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            if (claimed.compareAndSet(false, true)) {
                // 待機側が先に打ち切った: タスクは block を実行しないため副作用は起きない。
                default
            } else {
                // タスクが既に block 実行中: 実結果を待つ(block はキャッシュ操作のみで短時間)。
                try { future.get() } catch (t: Throwable) { default }
            }
        } catch (t: Throwable) {
            default
        }
    }
}
