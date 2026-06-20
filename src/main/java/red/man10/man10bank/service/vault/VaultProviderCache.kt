package red.man10.man10bank.service.vault

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider が同期応答に使うローカル Vault 台帳の読み取り面 + 未確定差分管理。
 *
 * 設計上、全更新は VaultService がメインスレッドへ直列化して適用する(設計書 §10.2)。
 * ただし getBalance などの読み取りが別経路から来ても破綻しないよう、エントリは不変(immutable)とし、
 * [ConcurrentHashMap] で原子的に差し替える。これにより読み取りはロックフリーで安全。
 *
 * availableBalance / visibleBalance は保存値ではなく `confirmedBalance + pendingDelta` の計算値。
 * pendingDelta は operationId ごとの未確定「減算」予約の合計(常に 0 以下)。未確定の入金は含めない(設計書 §5.3)。
 */
class VaultProviderCache {

    enum class Status {
        LOADING,      // 読み込み中
        WARMING_UP,   // join 直後のクールタイム中
        READY,        // 取引可能
        STALE,        // 古い可能性あり
        DRAINING,     // quit/transfer 後のキュー処理中
        CONFLICT,     // 競合停止中
        DISABLED      // 停止中
    }

    enum class PendingSource { PROVIDER, MAN10_API }

    /** operationId ごとの未確定減算予約。amount は減算の絶対値(正)。 */
    data class PendingVaultOperation(
        val operationId: String,
        val amount: Long,
        val source: PendingSource,
        val createdAtMillis: Long,
    )

    data class VaultCacheEntry(
        val uuid: UUID,
        val confirmedBalance: Long,
        val confirmedVersion: Long,
        val pendingOperations: Map<String, PendingVaultOperation>,
        val status: Status,
        val readyAfterMillis: Long?,
        val lastSyncedAtMillis: Long,
    ) {
        /** 未確定の減算予約の合計(常に 0 以下)。未確定の入金は含めない。 */
        fun pendingDelta(): Long = -pendingOperations.values.sumOf { it.amount }

        /** getBalance が返す表示残高。DB 未確定の入金は含めない。 */
        fun visibleBalance(): Long = confirmedBalance + pendingDelta()

        /** 出金/pay/vault->bank の可否判定に使う利用可能額。 */
        fun availableBalance(): Long = confirmedBalance + pendingDelta()
    }

    private val entries = ConcurrentHashMap<UUID, VaultCacheEntry>()

    fun get(uuid: UUID): VaultCacheEntry? = entries[uuid]

    fun remove(uuid: UUID) {
        entries.remove(uuid)
    }

    fun contains(uuid: UUID): Boolean = entries.containsKey(uuid)

    /** READY のオンライン UUID 一覧(再同期対象列挙用)。 */
    fun uuids(): Set<UUID> = entries.keys.toSet()

    /** 読み込み開始。LOADING のエントリを用意する(既存があれば status のみ LOADING に倒す)。 */
    fun beginLoad(uuid: UUID, nowMillis: Long) {
        entries.compute(uuid) { _, existing ->
            existing?.copy(status = Status.LOADING)
                ?: VaultCacheEntry(uuid, 0, 0, emptyMap(), Status.LOADING, null, nowMillis)
        }
    }

    /**
     * 初回ロード成功。confirmedBalance/version をセットし WARMING_UP にする。
     * readyAfterMillis 経過後に再取得して READY へ昇格する想定。
     */
    fun onInitialLoad(uuid: UUID, balance: Long, version: Long, readyAfterMillis: Long, nowMillis: Long) {
        entries.compute(uuid) { _, existing ->
            (existing ?: VaultCacheEntry(uuid, 0, 0, emptyMap(), Status.LOADING, null, nowMillis)).copy(
                confirmedBalance = balance,
                confirmedVersion = version,
                status = Status.WARMING_UP,
                readyAfterMillis = readyAfterMillis,
                lastSyncedAtMillis = nowMillis,
            )
        }
    }

    /** WARMING_UP のクールタイム経過後に権威残高を再取得して READY にする。 */
    fun promoteToReady(uuid: UUID, balance: Long, version: Long, nowMillis: Long) {
        entries.computeIfPresent(uuid) { _, e ->
            val applied = applyVersion(e, balance, version)
            applied.copy(status = Status.READY, readyAfterMillis = null, lastSyncedAtMillis = nowMillis)
        }
    }

    fun setStatus(uuid: UUID, status: Status) {
        entries.computeIfPresent(uuid) { _, e -> e.copy(status = status) }
    }

    /**
     * operationId ごとの減算予約を追加する。
     * status が READY かつ availableBalance >= amount のときだけ成功(true)。
     * @return 予約に成功したか
     */
    fun reserveDecrease(uuid: UUID, op: PendingVaultOperation): Boolean {
        var ok = false
        entries.computeIfPresent(uuid) { _, e ->
            if (e.status != Status.READY) return@computeIfPresent e
            if (e.pendingOperations.containsKey(op.operationId)) return@computeIfPresent e // 二重予約防止
            if (op.amount <= 0) return@computeIfPresent e
            if (e.availableBalance() < op.amount) return@computeIfPresent e
            ok = true
            e.copy(pendingOperations = e.pendingOperations + (op.operationId to op))
        }
        return ok
    }

    /** 予約を取り消す(失敗・キャンセル時)。 */
    fun cancelPending(uuid: UUID, operationId: String) {
        entries.computeIfPresent(uuid) { _, e ->
            if (!e.pendingOperations.containsKey(operationId)) e
            else e.copy(pendingOperations = e.pendingOperations - operationId)
        }
    }

    /**
     * 操作確定。対応する operationId の予約を外し、権威残高/version を反映する(version が新しければ)。
     * 入金確定(予約なし)にも使える。
     */
    fun confirm(uuid: UUID, operationId: String?, balance: Long, version: Long) {
        entries.computeIfPresent(uuid) { _, e ->
            val withoutPending = if (operationId != null) e.copy(pendingOperations = e.pendingOperations - operationId) else e
            applyVersion(withoutPending, balance, version)
        }
    }

    /**
     * 権威残高の反映(push / 定期再同期 / 初回ロード後の再取得)。
     * version が現在より新しい場合のみ confirmedBalance/version を更新し、未確定の減算予約は保持する。
     */
    fun applyAuthoritative(uuid: UUID, balance: Long, version: Long, nowMillis: Long) {
        entries.computeIfPresent(uuid) { _, e ->
            applyVersion(e, balance, version).copy(lastSyncedAtMillis = nowMillis)
        }
    }

    private fun applyVersion(e: VaultCacheEntry, balance: Long, version: Long): VaultCacheEntry {
        // 古い push/再同期は version で捨てる。現在 version 以上のときだけ反映する。
        return if (version >= e.confirmedVersion) e.copy(confirmedBalance = balance, confirmedVersion = version) else e
    }
}
