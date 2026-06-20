package red.man10.man10bank.service.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import red.man10.man10bank.Man10Bank
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.api.error.ApiHttpException
import red.man10.man10bank.api.model.request.VaultDepositRequest
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultSetRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.config.ConfigManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * プラグイン側の電子マネー操作の単一窓口(設計書 §7)。
 *
 * - 外部 Vault 互換経路(Provider)向けの同期メソッド: provider* 。呼び出し側(Provider)がメインスレッドを保証する。
 * - 内製 API 経路向けの非同期メソッド: getBalance/deposit/withdraw/transfer/move 系/setBalance(suspend)。
 *   いずれも Man10BankService の確定応答を待つ。減算操作は送信前にローカル Vault 台帳へ予約する。
 * - 送信待ちキューの処理・書き込み健全性監視・定期再同期・Provider キャッシュ収束を担う。
 *
 * Provider キャッシュ([VaultProviderCache])は原子的 per-key 更新でスレッド安全なため、
 * IO スレッドからの収束更新と Provider(メインスレッド)の読み書きが安全に共存する。
 */
class VaultService(
    private val plugin: Man10Bank,
    private val scope: CoroutineScope,
    private val vaultApi: VaultApiClient,
    private val serverName: String,
    private val config: ConfigManager.VaultConfig,
) {

    enum class WriteHealth { WRITE_READY, DEGRADED, DOWN, DRAINING }

    val cache = VaultProviderCache()
    private val queue = VaultWriteQueue(config.pendingThreshold)

    private val health = AtomicReference(WriteHealth.DOWN)
    @Volatile private var maxBalance: Long = -1L
    @Volatile private var joinReadyDelayMillis: Long = config.joinReadyDelayMillis
    @Volatile private var quitDrainTimeoutMillis: Long = config.quitDrainTimeoutMillis
    @Volatile private var lastServiceSuccessAtMillis: Long = 0L

    // Provider 登録状態(Man10Bank が登録成功後に true)。管理停止で disabled=true。
    @Volatile var registered: Boolean = false
    @Volatile private var disabled: Boolean = false

    private val running = AtomicReference(false)

    // ======================= ライフサイクル =======================

    /** バックグラウンド処理(キュー送信/健全性監視/定期再同期)を開始する。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        scope.launch { queueLoop() }
        scope.launch { healthLoop() }
        scope.launch { resyncLoop() }
    }

    fun shutdown() {
        running.set(false)
    }

    fun isProviderEnabled(): Boolean = registered && config.providerEnabled && !disabled

    fun setDisabled(value: Boolean) { disabled = value }

    fun writeHealth(): WriteHealth = health.get()

    fun maxBalanceOrUnset(): Long = maxBalance

    /** 書き込みを受け付けてよいか(Provider 同期判定の中核)。 */
    private fun writeReady(): Boolean =
        health.get() == WriteHealth.WRITE_READY && maxBalance > 0 && queue.isHealthy()

    // ======================= join/quit =======================

    /** join 時: LOADING にして権威残高をロードし、WARMING_UP -> クールタイム後 READY へ。 */
    fun onJoin(uuid: UUID) {
        cache.beginLoad(uuid, now())
        scope.launch {
            val res = vaultApi.getBalance(uuid)
            res.onSuccess { b ->
                markServiceSuccess()
                cache.onInitialLoad(uuid, b.balance, b.version, now() + joinReadyDelayMillis, now())
                delay(joinReadyDelayMillis)
                // クールタイム後に権威残高を再取得して READY 昇格(キュー健全時のみ)
                if (queue.isHealthy()) {
                    val again = vaultApi.getBalance(uuid)
                    again.onSuccess { b2 ->
                        markServiceSuccess()
                        cache.promoteToReady(uuid, b2.balance, b2.version, now())
                    }.onFailure {
                        // 再取得失敗時は STALE のまま据え置き(書き込みは拒否される)
                        cache.setStatus(uuid, VaultProviderCache.Status.STALE)
                    }
                }
            }.onFailure {
                cache.setStatus(uuid, VaultProviderCache.Status.STALE)
            }
        }
    }

    /** quit/kick/transfer 時: DRAINING にして対象 UUID のキューを可能な限り flush し、キャッシュを破棄する。 */
    fun onQuit(uuid: UUID) {
        cache.setStatus(uuid, VaultProviderCache.Status.DRAINING)
        scope.launch {
            val deadline = now() + quitDrainTimeoutMillis
            // 対象 UUID のキュー操作が残っている間、上限時間まで送信完了を待つ。
            while (now() < deadline && queue.snapshot().any { it.uuid == uuid }) {
                delay(100)
            }
            cache.remove(uuid)
        }
    }

    // ======================= Provider 同期メソッド(メインスレッド) =======================

    /** getBalance: READY/WARMING_UP なら visibleBalance、未ロードは 0。 */
    fun providerGetVisibleBalance(uuid: UUID): Long {
        val e = cache.get(uuid) ?: return 0
        return when (e.status) {
            VaultProviderCache.Status.READY,
            VaultProviderCache.Status.WARMING_UP -> e.visibleBalance()
            else -> 0
        }
    }

    /** has: READY かつ書き込み健全かつ availableBalance >= amount。 */
    fun providerHas(uuid: UUID, amount: Long): Boolean {
        if (amount <= 0) return false
        if (!writeReady()) return false
        val e = cache.get(uuid) ?: return false
        return e.status == VaultProviderCache.Status.READY && e.availableBalance() >= amount
    }

    /**
     * withdrawPlayer: 条件を満たせば減算予約 + 送信待ちキュー登録で SUCCESS(true)。
     * キュー登録に失敗したら予約を取り消して false。
     */
    fun providerWithdraw(uuid: UUID, amount: Long, pluginName: String?, reason: String?): Boolean {
        if (amount <= 0 || !writeReady()) return false
        val e = cache.get(uuid) ?: return false
        if (e.status != VaultProviderCache.Status.READY) return false

        val operationId = newOperationId()
        val reserved = cache.reserveDecrease(
            uuid,
            VaultProviderCache.PendingVaultOperation(operationId, amount, VaultProviderCache.PendingSource.PROVIDER, now())
        )
        if (!reserved) return false

        val ok = queue.enqueue(
            VaultWriteQueue.VaultQueuedOperation(
                operationId, serverName, uuid, VaultWriteQueue.Type.PROVIDER_WITHDRAW,
                amount, pluginName, reason, now()
            )
        )
        if (!ok) {
            cache.cancelPending(uuid, operationId) // キュー満杯: 予約を戻して失敗
            return false
        }
        return true
    }

    /**
     * depositPlayer: 条件を満たせば送信待ちキュー登録で SUCCESS(true)。
     * DB 確定まで Provider キャッシュ残高は増やさない(予約も作らない)。
     */
    fun providerDeposit(uuid: UUID, amount: Long, pluginName: String?, reason: String?): Boolean {
        if (amount <= 0 || !writeReady()) return false
        val e = cache.get(uuid) ?: return false
        if (e.status != VaultProviderCache.Status.READY) return false
        // 更新後残高が上限を超える操作は事前に弾く(送って 409 になるのを避ける)。
        if (e.confirmedBalance + amount > maxBalance) return false

        return queue.enqueue(
            VaultWriteQueue.VaultQueuedOperation(
                newOperationId(), serverName, uuid, VaultWriteQueue.Type.PROVIDER_DEPOSIT,
                amount, pluginName, reason, now()
            )
        )
    }

    /** hasAccount: オンラインかつキャッシュが LOADING/WARMING_UP/READY。 */
    fun providerHasAccount(uuid: UUID): Boolean {
        if (plugin.server.getPlayer(uuid) == null) return false
        val s = cache.get(uuid)?.status ?: return false
        return s == VaultProviderCache.Status.LOADING ||
            s == VaultProviderCache.Status.WARMING_UP ||
            s == VaultProviderCache.Status.READY
    }

    /** createPlayerAccount: オンライン対象の ensure/load 要求を受理できれば true。READY までは金銭操作は失敗する。 */
    fun providerCreateAccount(uuid: UUID): Boolean {
        if (plugin.server.getPlayer(uuid) == null) return false
        if (!cache.contains(uuid)) onJoin(uuid)
        return true
    }

    // ======================= 内製 API(非同期) =======================

    /** 権威残高取得。オンラインなら Provider キャッシュも収束させる。 */
    suspend fun getBalance(uuid: UUID): Long? {
        val res = vaultApi.getBalance(uuid)
        return res.fold(
            onSuccess = { b ->
                markServiceSuccess()
                if (cache.contains(uuid)) cache.applyAuthoritative(uuid, b.balance, b.version, now())
                b.balance
            },
            onFailure = { null }
        )
    }

    /** 電子マネー権威入金。対象が自サーバーにいなくても直接送れる唯一の電子マネー操作。 */
    suspend fun deposit(uuid: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount <= 0) return VaultResult.fail("金額が不正です")
        val req = VaultDepositRequest(uuid.toString(), amount, pluginName, reason, reason, serverName, newOperationId(), "MAN10_API")
        return vaultApi.deposit(req).fold(
            onSuccess = { b ->
                markServiceSuccess()
                if (cache.contains(uuid)) cache.confirm(uuid, null, b.balance, b.version)
                VaultResult.ok(b.balance)
            },
            onFailure = { VaultResult.fail(errorMessage(it)) }
        )
    }

    /** 電子マネー権威出金。自サーバー在席かつ READY のときだけローカル予約してから送る。 */
    suspend fun withdraw(uuid: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount <= 0) return VaultResult.fail("金額が不正です")
        val operationId = newOperationId()
        if (!reserveLocalDecrease(uuid, operationId, amount)) {
            return VaultResult.fail("残高が不足しているか、取引できない状態です")
        }
        val req = VaultWithdrawRequest(uuid.toString(), amount, pluginName, reason, reason, serverName, operationId, "MAN10_API")
        return vaultApi.withdraw(req).fold(
            onSuccess = { b ->
                markServiceSuccess()
                cache.confirm(uuid, operationId, b.balance, b.version)
                VaultResult.ok(b.balance)
            },
            onFailure = {
                cache.cancelPending(uuid, operationId)
                resyncIfCached(uuid)
                VaultResult.fail(errorMessage(it))
            }
        )
    }

    /** /pay。送金元・送金先が同一の自サーバー上でオンラインかつ双方 READY のときだけ実行する。 */
    suspend fun transfer(from: UUID, to: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount <= 0) return VaultResult.fail("金額が不正です")
        if (from == to) return VaultResult.fail("自分自身へは送金できません")
        // 送金先が自サーバーで READY であること(オンライン同一サーバー条件)。
        if (cache.get(to)?.status != VaultProviderCache.Status.READY) {
            return VaultResult.fail("送金先が同一サーバーでオンラインではありません")
        }
        val operationId = newOperationId()
        if (!reserveLocalDecrease(from, operationId, amount)) {
            return VaultResult.fail("残高が不足しているか、取引できない状態です")
        }
        val req = VaultTransferRequest(from.toString(), to.toString(), amount, pluginName, reason, reason, serverName, operationId)
        return vaultApi.transfer(req).fold(
            onSuccess = { r ->
                markServiceSuccess()
                cache.confirm(from, operationId, r.fromBalance, r.fromVersion)
                if (cache.contains(to)) cache.confirm(to, null, r.toBalance, r.toVersion)
                VaultResult.ok(r.fromBalance)
            },
            onFailure = {
                cache.cancelPending(from, operationId)
                resyncIfCached(from)
                VaultResult.fail(errorMessage(it))
            }
        )
    }

    /** /deposit。user_vault -> user_bank。vault 側を先にローカル予約してから move する。 */
    suspend fun moveVaultToBank(uuid: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount <= 0) return VaultResult.fail("金額が不正です")
        val operationId = newOperationId()
        if (!reserveLocalDecrease(uuid, operationId, amount)) {
            return VaultResult.fail("電子マネー残高が不足しているか、取引できない状態です")
        }
        val req = VaultMoveRequest(uuid.toString(), amount, "VaultToBank", pluginName, reason, reason, serverName, operationId)
        return vaultApi.move(req).fold(
            onSuccess = { r ->
                markServiceSuccess()
                cache.confirm(uuid, operationId, r.vaultBalance, r.vaultVersion)
                VaultResult.ok(r.vaultBalance)
            },
            onFailure = {
                cache.cancelPending(uuid, operationId)
                resyncIfCached(uuid)
                VaultResult.fail(errorMessage(it))
            }
        )
    }

    /** /withdraw。user_bank -> user_vault。電子マネーは増えるため予約はしない(自サーバー READY 必須)。 */
    suspend fun moveBankToVault(uuid: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount <= 0) return VaultResult.fail("金額が不正です")
        if (cache.get(uuid)?.status != VaultProviderCache.Status.READY) {
            return VaultResult.fail("自サーバーでオンライン・取引可能ではありません")
        }
        val req = VaultMoveRequest(uuid.toString(), amount, "BankToVault", plugin.name, reason, reason, serverName, newOperationId())
        return vaultApi.move(req).fold(
            onSuccess = { r ->
                markServiceSuccess()
                cache.confirm(uuid, null, r.vaultBalance, r.vaultVersion)
                VaultResult.ok(r.vaultBalance)
            },
            onFailure = { VaultResult.fail(errorMessage(it)) }
        )
    }

    /** 管理者の絶対値設定。在席状況を問わず送る。 */
    suspend fun setBalance(uuid: UUID, amount: Long, reason: String, pluginName: String = plugin.name): VaultResult {
        if (amount < 0) return VaultResult.fail("金額が不正です")
        val req = VaultSetRequest(uuid.toString(), amount, pluginName, reason, reason, serverName, newOperationId())
        return vaultApi.set(req).fold(
            onSuccess = { b ->
                markServiceSuccess()
                if (cache.contains(uuid)) cache.confirm(uuid, null, b.balance, b.version)
                VaultResult.ok(b.balance)
            },
            onFailure = { VaultResult.fail(errorMessage(it)) }
        )
    }

    // 自サーバー在席かつ READY のときだけローカル予約する(対象未ロード/未 READY は失敗)。
    private fun reserveLocalDecrease(uuid: UUID, operationId: String, amount: Long): Boolean {
        return cache.reserveDecrease(
            uuid,
            VaultProviderCache.PendingVaultOperation(operationId, amount, VaultProviderCache.PendingSource.MAN10_API, now())
        )
    }

    // ======================= バックグラウンド =======================

    private suspend fun queueLoop() {
        while (running.get() && scope.isActive) {
            val op = queue.pollFirst()
            if (op == null) {
                delay(200)
                continue
            }
            try {
                when (op.type) {
                    VaultWriteQueue.Type.PROVIDER_WITHDRAW -> {
                        val req = VaultWithdrawRequest(
                            op.uuid.toString(), op.amount, op.pluginName ?: "unknown",
                            op.reason ?: "vault-provider", op.reason ?: "電子マネー", op.serverName, op.operationId, "PROVIDER"
                        )
                        vaultApi.withdraw(req).fold(
                            onSuccess = { b ->
                                markServiceSuccess()
                                cache.confirm(op.uuid, op.operationId, b.balance, b.version)
                            },
                            onFailure = { handleQueueFailure(op, it) }
                        )
                    }
                    VaultWriteQueue.Type.PROVIDER_DEPOSIT -> {
                        val req = VaultDepositRequest(
                            op.uuid.toString(), op.amount, op.pluginName ?: "unknown",
                            op.reason ?: "vault-provider", op.reason ?: "電子マネー", op.serverName, op.operationId, "PROVIDER"
                        )
                        vaultApi.deposit(req).fold(
                            onSuccess = { b ->
                                markServiceSuccess()
                                cache.confirm(op.uuid, null, b.balance, b.version)
                            },
                            onFailure = { handleQueueFailure(op, it) }
                        )
                    }
                }
            } catch (t: Throwable) {
                handleQueueFailure(op, t)
            }
        }
    }

    // キュー送信失敗の扱い: 業務失敗(4xx)は CONFLICT として再送しない。通信/5xx は同一 operationId で再送待ち。
    private suspend fun handleQueueFailure(op: VaultWriteQueue.VaultQueuedOperation, t: Throwable) {
        val api = t as? ApiHttpException
        val status = api?.status?.value
        val business = status != null && status in 400..499
        if (business) {
            // SUCCESS 返却済みだが DB 側で失敗した重大不整合(設計書 §5.7)。
            if (op.type == VaultWriteQueue.Type.PROVIDER_WITHDRAW) cache.cancelPending(op.uuid, op.operationId)
            cache.setStatus(op.uuid, VaultProviderCache.Status.CONFLICT)
            plugin.logger.severe(
                "[vault] Provider 操作が DB 側で失敗(CONFLICT)。operationId=${op.operationId} uuid=${op.uuid} " +
                    "type=${op.type} amount=${op.amount} plugin=${op.pluginName} reason=${api?.problem?.title ?: t.message}"
            )
            resyncIfCached(op.uuid)
        } else {
            // 通信失敗/5xx: 健全性を落として先頭へ戻し、同一 operationId で再送する。
            health.set(if (now() - lastServiceSuccessAtMillis <= config.serviceToleranceMillis) WriteHealth.DEGRADED else WriteHealth.DOWN)
            queue.requeueFirst(op)
            delay(500)
        }
    }

    private suspend fun healthLoop() {
        while (running.get() && scope.isActive) {
            vaultApi.getConfig().fold(
                onSuccess = { cfg ->
                    maxBalance = cfg.maxBalance
                    joinReadyDelayMillis = cfg.joinReadyDelayMillis
                    quitDrainTimeoutMillis = cfg.quitDrainTimeoutMillis
                    markServiceSuccess()
                    if (queue.isHealthy()) health.set(WriteHealth.WRITE_READY) else health.set(WriteHealth.DEGRADED)
                },
                onFailure = {
                    health.set(if (now() - lastServiceSuccessAtMillis <= config.serviceToleranceMillis) WriteHealth.DEGRADED else WriteHealth.DOWN)
                }
            )
            delay(5000)
        }
    }

    private suspend fun resyncLoop() {
        while (running.get() && scope.isActive) {
            delay(config.resyncIntervalMillis)
            for (uuid in cache.uuids()) {
                vaultApi.getBalance(uuid).onSuccess { b ->
                    markServiceSuccess()
                    cache.applyAuthoritative(uuid, b.balance, b.version, now())
                }
            }
        }
    }

    private fun resyncIfCached(uuid: UUID) {
        if (!cache.contains(uuid)) return
        scope.launch {
            vaultApi.getBalance(uuid).onSuccess { b ->
                markServiceSuccess()
                cache.applyAuthoritative(uuid, b.balance, b.version, now())
            }
        }
    }

    private fun markServiceSuccess() { lastServiceSuccessAtMillis = now() }

    private fun newOperationId(): String = UUID.randomUUID().toString()

    private fun now(): Long = System.currentTimeMillis()

    private fun errorMessage(t: Throwable): String {
        val api = t as? ApiHttpException
        return api?.problem?.title ?: api?.problem?.detail ?: t.message ?: "不明なエラー"
    }

    data class VaultResult(val success: Boolean, val balance: Long = 0, val errorMessage: String? = null) {
        companion object {
            fun ok(balance: Long) = VaultResult(true, balance)
            fun fail(message: String) = VaultResult(false, errorMessage = message)
        }
    }
}
