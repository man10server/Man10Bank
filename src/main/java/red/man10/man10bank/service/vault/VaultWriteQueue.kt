package red.man10.man10bank.service.vault

import java.util.ArrayDeque
import java.util.UUID

/**
 * Provider が同期成功させた操作を後で Man10BankService へ送るための送信待ちキュー。
 * 各操作は二重適用を防ぐ冪等キー(operationId)を持つ(設計書 §6.4)。
 *
 * 通常時はメモリ上のこのキューへ登録できれば Provider は SUCCESS を返す。
 * Man10BankService 不調時の永続退避(SQLite/追記ログ)は本実装では未対応(設計書 §15.3 に保留として記載)。
 * その間の Paper クラッシュによる未送信操作消失は、設計書 §15.1 の許容済み既知リスクと同種として扱う。
 *
 * スレッド安全性: enqueue は同期 Provider(メインスレッド)から、drain は IO ワーカーから呼ばれるため synchronized で保護する。
 */
class VaultWriteQueue(private val maxPending: Int) {

    enum class Type { PROVIDER_DEPOSIT, PROVIDER_WITHDRAW }

    data class VaultQueuedOperation(
        val operationId: String,
        val serverName: String,
        val uuid: UUID,
        val type: Type,
        val amount: Long,
        val pluginName: String?,
        val reason: String?,
        val createdAtMillis: Long,
    )

    private val deque = ArrayDeque<VaultQueuedOperation>()

    /**
     * 末尾に登録する。未処理件数が上限以上なら登録せず false を返す(Provider は FAILURE にする)。
     */
    @Synchronized
    fun enqueue(op: VaultQueuedOperation): Boolean {
        if (deque.size >= maxPending) return false
        deque.addLast(op)
        return true
    }

    /** 先頭を取り出す(送信処理用)。空なら null。 */
    @Synchronized
    fun pollFirst(): VaultQueuedOperation? = deque.pollFirst()

    /** 通信失敗時に先頭へ戻す(同一 operationId で再送する)。 */
    @Synchronized
    fun requeueFirst(op: VaultQueuedOperation) {
        deque.addFirst(op)
    }

    @Synchronized
    fun size(): Int = deque.size

    /** 未処理件数が上限以下で健全か。 */
    fun isHealthy(): Boolean = size() < maxPending

    @Synchronized
    fun snapshot(): List<VaultQueuedOperation> = deque.toList()

    @Synchronized
    fun clear() = deque.clear()
}
