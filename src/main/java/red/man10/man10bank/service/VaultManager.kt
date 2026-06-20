package red.man10.man10bank.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.command.balance.BalanceRegistry
import red.man10.man10bank.service.vault.VaultService
import red.man10.man10bank.util.BalanceFormats

/**
 * 電子マネー(Vault)操作のファサード(設計書 §11.1)。
 *
 * 旧実装は外部 Economy Provider を取得する Consumer だったが、Man10Bank 自身が Provider になったため、
 * 本クラスは [VaultService] への薄い委譲に作り替えた。既存呼び出し側のシグネチャは維持する。
 * `ServicesManager.getRegistration(Economy)` で自分自身の Provider を取得する実装は禁止(設計書 §11.1)。
 *
 * - 残高参照は Provider キャッシュ(VaultService)から行う。
 * - depositOnMain/withdrawOnMain は VaultService の権威操作へ委譲する(互換用)。
 */
class VaultManager(private val plugin: JavaPlugin) {

    @Volatile private var service: VaultService? = null
    @Volatile private var scope: CoroutineScope? = null

    /** Man10Bank 起動時に VaultService を紐付ける。 */
    fun attach(service: VaultService, scope: CoroutineScope) {
        this.service = service
        this.scope = scope
    }

    /** 旧 API 互換の no-op。外部 Economy は取得しない(自身が Provider)。 */
    @Deprecated("Man10Bank 自身が Vault Provider になったため外部 Economy 取得は行わない")
    fun hook(): Boolean = service != null

    /** 電子マネーサービスが利用可能か。 */
    fun isAvailable(): Boolean = service != null

    /** 旧 API 互換。外部 Economy Provider は保持しない。 */
    @Deprecated("外部 Economy Provider は取得しない")
    fun provider(): Nothing? = null

    /** 残高取得(同期/メインスレッド)。Provider キャッシュの visibleBalance を返す。未ロードは 0.0。 */
    fun getBalance(player: OfflinePlayer): Double =
        service?.providerGetVisibleBalance(player.uniqueId)?.toDouble() ?: 0.0

    /** 残高取得(権威/非同期)。Man10BankService から取得し、オンラインならキャッシュも収束。 */
    suspend fun getBalanceOnMain(player: OfflinePlayer): Double =
        service?.getBalance(player.uniqueId)?.toDouble() ?: 0.0

    /** 権威入金(互換用)。VaultService の deposit へ委譲。 */
    suspend fun depositOnMain(player: OfflinePlayer, amount: Double): Boolean =
        service?.deposit(player.uniqueId, amount.toLong(), "bank-compat")?.success ?: false

    /** 権威出金(互換用)。VaultService の withdraw へ委譲。 */
    suspend fun withdrawOnMain(player: OfflinePlayer, amount: Double): Boolean =
        service?.withdraw(player.uniqueId, amount.toLong(), "bank-compat")?.success ?: false

    /**
     * 管理者用の絶対値設定(在席状況を問わない)。VaultService.setBalance へ委譲し、結果をメインスレッドで通知する。
     */
    fun setBalanceAsync(player: OfflinePlayer, amount: Long, reason: String, onResult: (VaultService.VaultResult) -> Unit) {
        val s = service
        val sc = scope
        if (s == null || sc == null) {
            onResult(VaultService.VaultResult.fail("電子マネーサービスが利用できません"))
            return
        }
        sc.launch {
            val r = s.setBalance(player.uniqueId, amount, reason)
            plugin.server.scheduler.runTask(plugin, Runnable { onResult(r) })
        }
    }

    /** 残高表示プロバイダの登録(電子マネー/Vault)。表示値はメインスレッド収集済みの context から取得する。 */
    fun registerBalanceProvider() {
        BalanceRegistry.register(
            id = "vault",
            order = 10,
            provider = { _, context ->
                "§b§l電子マネー: ${BalanceFormats.coloredYen(context.vaultBalance)}§r"
            }
        )
    }
}
