package red.man10.man10bank.command.balance
import org.bukkit.entity.Player
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.BankApiClient
import red.man10.man10bank.service.VaultManager
import red.man10.man10bank.service.CashItemManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 所持金表示用のプロバイダ登録レジストリ。
 * 外部から `register(id, order, provider)` で表示セクションを追加できます。
 */
object BalanceRegistry {

    // id -> (order, provider)
    private val providers = ConcurrentHashMap<String, Pair<Int, Provider>>()

    /** 表示用の依存コンテキスト。 */
    data class Context(
        val plugin: Man10Bank,
        val bank: BankApiClient,
        val vault: VaultManager,
        val cash: CashItemManager,
    )

    /** 表示データを提供する関数型インターフェイス。 */
    fun interface Provider {
        /**
         * プレイヤー向けの表示行を返します（カラーコード可）。
         * 例: listOf("§e§l銀行残高: 1,234,567§r")
         */
        suspend fun line(player: Player, ctx: Context): String
    }

    /** 表示セクションを追加（同一idは上書き）。orderが小さい順に表示。 */
    fun register(id: String, order: Int = 0, provider: Provider) {
        providers[id] = order to provider
    }

    /** 登録済みセクションを結合して表示行を返す。 */
    suspend fun buildLines(player: Player, ctx: Context): List<String> {
        return providers.entries
            .sortedBy { it.value.first }
            .map { entry ->
                val id = entry.key
                val provider = entry.value.second
                runCatching { provider.line(player, ctx) }
                    .getOrElse { "§7[${id}] の表示に失敗: ${it.message}" }
            }
            .filter { it.isNotBlank() }
    }
}
