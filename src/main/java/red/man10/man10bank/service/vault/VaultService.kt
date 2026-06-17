package red.man10.man10bank.service.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin
import red.man10.man10bank.api.VaultApiClient
import red.man10.man10bank.api.model.request.VaultDepositRequest
import red.man10.man10bank.api.model.request.VaultMoveDirection
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultSetRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.api.model.response.VaultBalanceResponse
import red.man10.man10bank.api.model.response.VaultMoveResponse
import red.man10.man10bank.util.Messages
import red.man10.man10bank.util.errorMessage
import java.io.IOException
import java.util.UUID

/** 管理用の電子マネー操作種別（/meco give|take|set）。 */
enum class VaultAdminOp { GIVE, TAKE, SET }

/**
 * 電子マネーの単一窓口（VaultProvider 8.1）。
 *
 * - Vault(Economy) 契約用の同期 API（[getBalance]/[has]/[deposit]/[withdraw]）。
 *   読みはキャッシュ即返し、書きはキャッシュを楽観更新し REST へ非同期 write-through する。
 * - `/pay`・move など確定応答を要する操作は suspend で REST を await し確定結果を返す。
 *
 * スレッドモデル（8.4）:
 * - 同期 API はメインスレッドで完結（キャッシュ参照のみ）。
 * - write-through・resync は [scope]（Dispatchers.IO）で実行し、[VaultCache] を介して受け渡す。
 */
class VaultService(
    private val plugin: JavaPlugin,
    private val serverName: String,
    private val scope: CoroutineScope,
    private val api: VaultApiClient,
    val cache: VaultCache,
) {
    @Volatile
    private var connected = false

    /** 接続レベル障害を検知したときに WebSocket の再接続を促すフック（[VaultSyncClient.requestReconnect]）。 */
    @Volatile
    private var reconnectRequester: (() -> Unit)? = null

    /** 同期接続（WebSocket）の状態。[VaultSyncClient] から更新する。 */
    fun setConnected(value: Boolean) {
        connected = value
    }

    /** 接続レベル障害検知時に WebSocket 再接続を促すフックを登録する（[Man10Bank] が配線する）。 */
    fun setReconnectRequester(requester: () -> Unit) {
        reconnectRequester = requester
    }

    /** Economy.isEnabled 用。プラグイン有効かつ同期接続済み。 */
    fun isReady(): Boolean = plugin.isEnabled && connected

    /**
     * 接続レベル（トランスポート）の失敗なら、WebSocket の切断検知を待たずに **即 fail-closed** にし、
     * WS 再接続を促す（VaultProvider 4.6 ②）。`IOException`（Connection refused / connect・request timeout 等）が対象。
     * `ApiHttpException`（4xx/5xx＝サービスは応答している）は対象外。
     * 復帰可否の判断は再接続に委ねるため、一過性ブリップでも固着しない（成功すれば即 connected=true に戻る）。
     */
    private fun signalConnectionLossIfTransport(ex: Throwable?) {
        // 例外がラップされている場合に備え cause 連鎖も見る（深さは安全のため上限を設ける）。
        val isTransport = generateSequence(ex) { it.cause }.take(10).any { it is IOException }
        if (isTransport) {
            setConnected(false)
            reconnectRequester?.invoke()
        }
    }

    // === 同期 Vault 契約 ===

    /**
     * 残高取得（未キャッシュは 0.0）。未キャッシュかつ当該サーバーにオンラインなら非同期ロードを促す
     * （ベストエフォート。次回呼び出しから正しい値を返す。VaultProvider 4.5）。
     */
    fun getBalance(uuid: UUID): Double {
        if (!cache.contains(uuid) && plugin.server.getPlayer(uuid) != null) {
            scope.launch { loadIntoCache(uuid) }
        }
        return cache.get(uuid)
    }

    /** `balance >= amount` 判定（楽観・未キャッシュは false）。 */
    fun has(uuid: UUID, amount: Double): Boolean = cache.has(uuid, amount)

    /**
     * 入金（Vault 契約）。キャッシュを楽観加算し REST を非同期投入、即 SUCCESS を返す。
     * 未キャッシュ（オフライン/他サーバー在席）は service を呼ばず即 FAILURE（VaultProvider 4.5）。
     */
    fun deposit(player: OfflinePlayer, amount: Double): EconomyResponse {
        val amt = normalize(amount)
        if (amt <= 0.0) return failure("金額が不正です。")
        // サービス未接続中は楽観更新を行わず即 FAILURE（真実優先・fail-closed。VaultProvider 4.6）。
        // 未確定の電子マネーを SUCCESS として外部(ショップ等)に渡すと、write-through 不達で増殖の原因になる。
        if (!isReady()) return failure("電子マネーに接続できないため操作できません。後でもう一度お試しください。")
        val uuid = player.uniqueId
        if (!cache.contains(uuid)) {
            return failure("対象がオフラインのため電子マネーを操作できません。")
        }
        val newBalance = cache.optimisticDeposit(uuid, amt)
            ?: return failure("対象がオフラインのため電子マネーを操作できません。")

        writeThrough(uuid, player.name.orEmpty(), amt, deposit = true, note = "VaultDeposit", displayNote = "電子マネー入金")
        return success(amt, newBalance)
    }

    /**
     * 出金（Vault 契約）。キャッシュ残高不足なら即 FAILURE、足りれば楽観減算し REST を非同期投入、即 SUCCESS。
     * 未キャッシュは即 FAILURE（VaultProvider 4.2/4.5）。
     */
    fun withdraw(player: OfflinePlayer, amount: Double): EconomyResponse {
        val amt = normalize(amount)
        if (amt <= 0.0) return failure("金額が不正です。")
        // サービス未接続中は楽観更新を行わず即 FAILURE（真実優先・fail-closed。VaultProvider 4.6）。
        if (!isReady()) return failure("電子マネーに接続できないため操作できません。後でもう一度お試しください。")
        val uuid = player.uniqueId
        if (!cache.contains(uuid)) {
            return failure("対象がオフラインのため電子マネーを操作できません。")
        }
        val newBalance = cache.optimisticWithdraw(uuid, amt)
            ?: return failure("残高が不足しています。")

        writeThrough(uuid, player.name.orEmpty(), amt, deposit = false, note = "VaultWithdraw", displayNote = "電子マネー出金")
        return success(amt, newBalance)
    }

    /** 口座を冪等作成する（createPlayerAccount）。非同期で ensure を投入しキャッシュへ反映する。 */
    fun ensureAccount(uuid: UUID, name: String): Boolean {
        // 未接続中は作成を試みない（接続後の resync / 次回 join のプリロードで反映される。VaultProvider 4.6）。
        if (!isReady()) return true
        scope.launch {
            api.ensureAccount(uuid).onSuccess { res ->
                // 既にオンライン（在席）のときだけキャッシュへ載せる。
                if (plugin.server.getPlayer(uuid) != null) {
                    cache.preload(uuid, res.balance, res.version)
                }
            }.onFailure {
                // 失敗は診断のため記録する（口座作成は冪等なので致命ではない）。
                plugin.logger.fine("口座の冪等作成に失敗しました uuid=$uuid: ${it.message}")
            }
        }
        return true
    }

    // === 確定応答が必要な操作（suspend） ===

    /**
     * 電子マネー送金（/pay）。送受信者がともに同一サーバーに在席している前提（呼び出し側で判定）。
     * 成功時は送金元の確定残高を返し、送金元キャッシュを補正する（送金先は push で収束）。
     */
    suspend fun transfer(fromUuid: UUID, toUuid: UUID, amount: Double, note: String, displayNote: String): Result<Double> {
        val amt = normalize(amount)
        if (amt <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。"))
        // 待てる確定経路も未接続なら早期失敗（fail-closed の一貫性。VaultProvider 4.6）。
        if (!isReady()) return Result.failure(IllegalStateException("電子マネーに接続できません。"))

        val result = api.transfer(
            VaultTransferRequest(
                fromUuid = fromUuid.toString(),
                toUuid = toUuid.toString(),
                amount = amt,
                pluginName = plugin.name,
                note = note,
                displayNote = displayNote,
                server = serverName,
            )
        )
        result.onSuccess { res -> cache.reconcile(fromUuid, res.balance, res.version) }
            .onFailure { signalConnectionLossIfTransport(it) }
        return result.map { it.balance }
    }

    /**
     * 電子マネー ⇄ 銀行残高の移動（ATM/`/deposit`/`/withdraw` 用）。
     * 成功時は両残高を返し、電子マネー側キャッシュを確定値へ補正する。
     */
    suspend fun move(
        uuid: UUID,
        amount: Double,
        direction: VaultMoveDirection,
        note: String,
        displayNote: String,
    ): Result<VaultMoveResponse> {
        val amt = normalize(amount)
        if (amt <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。"))
        // 待てる確定経路も未接続なら早期失敗（fail-closed の一貫性。VaultProvider 4.6）。
        if (!isReady()) return Result.failure(IllegalStateException("電子マネーに接続できません。"))

        val result = api.move(
            VaultMoveRequest(
                uuid = uuid.toString(),
                amount = amt,
                direction = direction,
                pluginName = plugin.name,
                note = note,
                displayNote = displayNote,
                server = serverName,
            )
        )
        result.onSuccess { res -> cache.reconcile(uuid, res.vaultBalance, res.vaultVersion) }
            .onFailure { signalConnectionLossIfTransport(it) }
        return result
    }

    /**
     * 入金を確定（REST await）して結果を返す（ATM 等の確定応答経路。VaultProvider 4.6）。
     * 楽観 [deposit] と異なり SUCCESS を即返さず、サービス側 user_vault が確定してから返す。
     * これにより「現金消費後に入金が不達」という増殖/消失を防ぐ。
     * 成功時のみ確定残高+version でキャッシュを補正する。未接続/失敗は Result.failure。
     */
    suspend fun depositConfirmed(uuid: UUID, amount: Double, note: String, displayNote: String): Result<VaultBalanceResponse> {
        val amt = normalize(amount)
        if (amt <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。"))
        if (!isReady()) return Result.failure(IllegalStateException("電子マネーに接続できません。"))
        val result = api.deposit(VaultDepositRequest(uuid.toString(), amt, plugin.name, note, displayNote, serverName))
        result.onSuccess { res -> cache.reconcile(uuid, res.balance, res.version) }
            .onFailure { signalConnectionLossIfTransport(it) }
        return result
    }

    /**
     * 出金を確定（REST await）。サービス側が行ロック下で残高を再チェックし、不足は 409。
     * 成功時のみ確定残高+version でキャッシュを補正する。未接続/不足/失敗は Result.failure。
     */
    suspend fun withdrawConfirmed(uuid: UUID, amount: Double, note: String, displayNote: String): Result<VaultBalanceResponse> {
        val amt = normalize(amount)
        if (amt <= 0.0) return Result.failure(IllegalArgumentException("金額が不正です。"))
        if (!isReady()) return Result.failure(IllegalStateException("電子マネーに接続できません。"))
        val result = api.withdraw(VaultWithdrawRequest(uuid.toString(), amt, plugin.name, note, displayNote, serverName))
        result.onSuccess { res -> cache.reconcile(uuid, res.balance, res.version) }
            .onFailure { signalConnectionLossIfTransport(it) }
        return result
    }

    /**
     * 管理用: 電子マネー残高を操作し、確定結果を返す（/meco give|take|set）。
     * - GIVE/TAKE は相対加減算（POST /api/Vault/deposit, /withdraw）。在席判定は呼び出し側で行う。
     * - SET は絶対値設定（POST /api/Vault/set）。オフライン（当該サーバー未キャッシュ）でも実行できる
     *   唯一の正規経路（VaultProvider 4.5）。
     * - 金額検証: GIVE/TAKE は正の数、SET は 0 以上。範囲外は呼び出さず失敗を返す。
     * - 成功時は確定残高+version でキャッシュを補正する（reconcile は在席時のみ反映、未キャッシュは無視）。
     */
    suspend fun adminOperate(
        uuid: UUID,
        op: VaultAdminOp,
        amount: Double,
        note: String,
        displayNote: String,
    ): Result<VaultBalanceResponse> {
        val amt = normalize(amount)
        val invalid = if (op == VaultAdminOp.SET) amt < 0.0 else amt <= 0.0
        if (invalid) return Result.failure(IllegalArgumentException("金額が不正です。"))
        // 管理操作も未接続なら早期失敗（fail-closed の一貫性。VaultProvider 4.6）。
        if (!isReady()) return Result.failure(IllegalStateException("電子マネーに接続できません。"))

        val result = when (op) {
            VaultAdminOp.GIVE -> api.deposit(
                VaultDepositRequest(uuid.toString(), amt, plugin.name, note, displayNote, serverName)
            )
            VaultAdminOp.TAKE -> api.withdraw(
                VaultWithdrawRequest(uuid.toString(), amt, plugin.name, note, displayNote, serverName)
            )
            VaultAdminOp.SET -> api.set(
                VaultSetRequest(uuid.toString(), amt, plugin.name, note, displayNote, serverName)
            )
        }
        result.onSuccess { res -> cache.reconcile(uuid, res.balance, res.version) }
            .onFailure { signalConnectionLossIfTransport(it) }
        return result
    }

    // === ライフサイクル ===

    /** join 時の電子マネープリロード（権威残高をキャッシュへ載せる）。 */
    suspend fun preload(uuid: UUID) {
        api.getBalance(uuid).onSuccess { res ->
            cache.preload(uuid, res.balance, res.version)
        }.onFailure {
            plugin.logger.warning("電子マネーのプリロードに失敗しました uuid=$uuid: ${it.message}")
        }
    }

    /** quit 時のキャッシュ退避。 */
    fun evict(uuid: UUID) {
        cache.evict(uuid)
    }

    // === 内部 ===

    private suspend fun loadIntoCache(uuid: UUID) {
        api.getBalance(uuid).onSuccess { res ->
            // ロード完了時点でまだ在席し、かつ未キャッシュのときだけ初期投入する。
            // reconcile/applyAuthoritative は computeIfPresent のため不在キーには載らない。
            // ここは「不在＋在席」専用経路なので preload（無条件セット）で初期投入する（VaultProvider 4.5）。
            if (plugin.server.getPlayer(uuid) != null && !cache.contains(uuid)) {
                cache.preload(uuid, res.balance, res.version)
            }
        }
    }

    /**
     * REST への非同期 write-through（VaultProvider 4.2/4.4）。
     * - 成功: 返却された確定残高+version でキャッシュを補正（version 方式で push と冪等）。
     * - 失敗(409/ネットワーク): 権威残高を再取得しキャッシュを上書き、構造化ログを残す。
     *   リトライで差分を二重適用しない（POST はリトライしない設計）。
     */
    private fun writeThrough(uuid: UUID, name: String, amount: Double, deposit: Boolean, note: String, displayNote: String) {
        scope.launch {
            val result = if (deposit) {
                api.deposit(VaultDepositRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName))
            } else {
                api.withdraw(VaultWithdrawRequest(uuid.toString(), amount, plugin.name, note, displayNote, serverName))
            }

            result.onSuccess { res ->
                cache.reconcile(uuid, res.balance, res.version)
            }.onFailure { ex ->
                // 追跡用のエラーID。同一IDをログとプレイヤー通知の双方へ出し、突き合わせられるようにする。
                val errorId = newErrorId()
                // 誰が(MinecraftID/uuid)・いくらの取引で(金額)失敗したかを追跡できるよう、ログにIDと氏名も残す。
                plugin.logger.severe(
                    "write-through失敗[${if (deposit) "deposit" else "withdraw"}] エラーID=$errorId " +
                        "MinecraftID=${name.ifBlank { "(不明)" }} uuid=$uuid 金額=$amount " +
                        "詳細=権威残高を再取得してキャッシュを補正します: ${result.errorMessage()}"
                )
                // 接続レベル障害なら即 fail-closed＋WS 再接続を促す（切断検知を待たずに楽観経路の漏れを止める。VaultProvider 4.6 ②）。
                signalConnectionLossIfTransport(ex)
                reconcileFromAuthority(uuid)
                // 在席中のプレイヤーへエラー発生とエラーIDを通知する（オフライン時はログのみで追跡）。
                // 通知はベストエフォートのため、金額補正（reconcile）を確実に実行した後、最後に行う。
                notifyWriteFailure(uuid, errorId)
            }
        }
    }

    /** 追跡用の短いエラーID（8桁英大文字）。ログとプレイヤー通知の突き合わせに使う。 */
    private fun newErrorId(): String = UUID.randomUUID().toString().take(8).uppercase()

    /**
     * write-through 失敗を在席中のプレイヤーへ通知する（VaultProvider 4.4）。
     * - 通知内容は「Man10Bank 上でエラーが発生したこと＋エラーID」。同IDは severe ログにも出ており突き合わせ可能。
     * - [scope]（IO）から呼ばれるため、在席判定と送信はメインスレッドのタスクで行う。
     * - オフライン（不在）なら送らない（追跡はログのエラーIDで担保）。
     * - ベストエフォート: 無効化中などで runTask が失敗しても、呼び出し元（金額補正）へ例外を波及させない。
     */
    private fun notifyWriteFailure(uuid: UUID, errorId: String) {
        runCatching {
            plugin.server.scheduler.runTask(plugin, Runnable {
                val player = plugin.server.getPlayer(uuid) ?: return@Runnable
                Messages.error(
                    player,
                    "Man10Bank上でエラーが発生しました。エラーIDを管理者にお伝えください。 エラーID: §e§l$errorId",
                )
            })
        }.onFailure { plugin.logger.fine("エラー通知のディスパッチに失敗しました（無効化中など）: ${it.message}") }
    }

    /** 権威残高を再取得してキャッシュを補正する（楽観ドリフトの矯正）。 */
    private suspend fun reconcileFromAuthority(uuid: UUID) {
        api.getBalance(uuid).onSuccess { res ->
            cache.reconcile(uuid, res.balance, res.version)
        }.onFailure {
            plugin.logger.severe("権威残高の再取得にも失敗しました uuid=$uuid: ${it.message}")
            signalConnectionLossIfTransport(it)
        }
    }

    /** 金額の正規化: 小数点以下を切り捨てて整数（Double）にする（fractionalDigits=0）。 */
    private fun normalize(amount: Double): Double = amount.toLong().toDouble()

    private fun success(amount: Double, newBalance: Double): EconomyResponse =
        EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, "")

    private fun failure(message: String): EconomyResponse =
        EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, message)
}
