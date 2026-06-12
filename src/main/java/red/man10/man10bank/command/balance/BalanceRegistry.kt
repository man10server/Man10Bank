package red.man10.man10bank.command.balance
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

/**
 * 所持金表示用のプロバイダ登録レジストリ。
 * 外部から `register(id, order, provider)` で表示セクションを追加できます。
 *
 * スレッド安全性（DESIGN 3.5）:
 * - 各 Provider は Dispatchers.IO（非メインスレッド）上で呼ばれるため、
 *   Bukkit/Vault API（残高・インベントリ）を直接呼んではならない。
 * - Bukkit 依存の値（電子マネー残高・現金合計など）は呼び出し側がメインスレッドで
 *   事前に収集し [BalanceContext] として渡す。Provider はこのコンテキストから読み取る。
 */
object BalanceRegistry {

    /**
     * メインスレッドで事前収集した Bukkit 依存の表示値スナップショット。
     * - Provider はこの値を参照することで非メインスレッドからの Bukkit API 呼び出しを避ける。
     */
    data class BalanceContext(
        /** 電子マネー（Vault）残高。 */
        val vaultBalance: Double,
        /** インベントリ+エンダーチェストの現金合計。 */
        val cashTotal: Double,
    )

    // id -> (order, provider)
    private val providers = ConcurrentHashMap<String, Pair<Int, Provider>>()

    /** 表示データを提供する関数型インターフェイス。 */
    fun interface Provider {
        /**
         * プレイヤー向けの表示行を返します（カラーコード可）。
         * - [context] にはメインスレッドで収集済みの Bukkit 依存値が入る。
         *   Bukkit/Vault に触れる値は必ず [context] 経由で参照すること。
         * 例: listOf("§e§l銀行残高: 1,234,567§r")
         */
        suspend fun line(player: Player, context: BalanceContext): String
    }

    /** 表示セクションを追加（同一idは上書き）。orderが小さい順に表示。 */
    fun register(id: String, order: Int = 0, provider: Provider) {
        providers[id] = order to provider
    }

    /** 登録済みセクションを結合して表示行を返す。 */
    suspend fun buildLines(player: Player, context: BalanceContext): List<String> {
        return providers.entries
            .sortedBy { it.value.first }
            .map { entry ->
                val id = entry.key
                val provider = entry.value.second
                runCatching { provider.line(player, context) }
                    .getOrElse { "§7[${id}] の表示に失敗: ${it.message}" }
            }
            .filter { it.isNotBlank() }
    }
}
