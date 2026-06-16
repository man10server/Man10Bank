package red.man10.man10bank.service.vault

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 電子マネー残高のローカルキャッシュ（VaultProvider 4）。
 *
 * - 読みは同期・スレッド安全（[ConcurrentHashMap]）。getBalance/has はメインスレッドから即返す。
 * - 書きは「楽観更新（[optimisticDeposit]/[optimisticWithdraw]）」と「権威適用」の2系統。
 *   権威適用は version 方式で冪等かつ順序安全:
 *     - [applyAuthoritative]: push 用。`version > 現在` のときだけ反映（自己修復）。
 *     - [reconcile]: write-through 成功/失敗後の補正用。`version >= 現在` で反映（楽観ドリフトを矯正）。
 * - キャッシュは「オンラインのみ常駐」（join で [preload]、quit で [evict]）。
 *   未キャッシュ（オフライン/他サーバー在席）への push は [applyAuthoritative] が no-op で無視する。
 */
class VaultCache {

    data class Entry(val balance: Double, val version: Long)

    // 楽観 withdraw の境界判定に使う微小許容（Double 累積誤差対策）。
    private val epsilon = 1e-6

    private val map = ConcurrentHashMap<UUID, Entry>()

    /** キャッシュ済み（=このサーバーに在席）か。 */
    fun contains(uuid: UUID): Boolean = map.containsKey(uuid)

    /** 残高（未キャッシュは 0.0）。 */
    fun get(uuid: UUID): Double = map[uuid]?.balance ?: 0.0

    /** version（未キャッシュは 0）。 */
    fun getVersion(uuid: UUID): Long = map[uuid]?.version ?: 0L

    /** `balance >= amount` 判定。未キャッシュは false。 */
    fun has(uuid: UUID, amount: Double): Boolean {
        val e = map[uuid] ?: return false
        return e.balance + epsilon >= amount
    }

    /** join プリロード/resync 用の無条件セット。 */
    fun preload(uuid: UUID, balance: Double, version: Long) {
        map[uuid] = Entry(balance, version)
    }

    /** quit 退避。 */
    fun evict(uuid: UUID) {
        map.remove(uuid)
    }

    /** 在席（キャッシュ済み）UUID の集合スナップショット。 */
    fun onlineUuids(): Set<UUID> = HashSet(map.keys)

    /**
     * 楽観入金。キャッシュ済みなら `+= amount` して新残高を返す。未キャッシュは null（拒否）。
     * version は変更しない（権威確定は write-through/push で行う）。
     */
    fun optimisticDeposit(uuid: UUID, amount: Double): Double? {
        var newBalance: Double? = null
        map.computeIfPresent(uuid) { _, e ->
            val nb = e.balance + amount
            newBalance = nb
            e.copy(balance = nb)
        }
        return newBalance
    }

    /**
     * 楽観出金。キャッシュ済みかつ残高十分なら `-= amount` して新残高を返す。
     * 未キャッシュ・残高不足は null（拒否）。version は変更しない。
     */
    fun optimisticWithdraw(uuid: UUID, amount: Double): Double? {
        var newBalance: Double? = null
        map.computeIfPresent(uuid) { _, e ->
            if (e.balance + epsilon < amount) {
                e // 残高不足: 変更しない
            } else {
                val nb = e.balance - amount
                newBalance = nb
                e.copy(balance = nb)
            }
        }
        return newBalance
    }

    /**
     * push 適用（version 方式）。キャッシュ済みかつ `version > 現在.version` のときだけ反映する。
     * 未キャッシュ（オフライン）は何もしない。冪等かつ順序安全。反映したら true。
     */
    fun applyAuthoritative(uuid: UUID, balance: Double, version: Long): Boolean {
        var applied = false
        map.computeIfPresent(uuid) { _, e ->
            if (version > e.version) {
                applied = true
                Entry(balance, version)
            } else {
                e
            }
        }
        return applied
    }

    /**
     * write-through 成功/失敗後の補正。キャッシュ済みかつ `version >= 現在.version` で反映する。
     * 楽観更新は version を進めないため、同 version でも残高ドリフトを矯正できる。未キャッシュは無視。
     */
    fun reconcile(uuid: UUID, balance: Double, version: Long) {
        map.computeIfPresent(uuid) { _, e ->
            if (version >= e.version) Entry(balance, version) else e
        }
    }
}
