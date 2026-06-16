package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.VaultDepositRequest
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultSetRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.api.model.response.MoneyLog
import red.man10.man10bank.api.model.response.VaultBalanceResponse
import red.man10.man10bank.api.model.response.VaultMoveResponse
import java.util.UUID

/**
 * /api/Vault 系の WebAPI クライアント（[BankApiClient] と同形）。
 * - 残高系は `{ balance, version }`（[VaultBalanceResponse]）を返す。
 * - move は両残高（[VaultMoveResponse]）を返す。
 * - 非2xx は HttpClientFactory の HttpResponseValidator により ApiHttpException へ正規化され、
 *   Result.failure として伝播する（POST はリトライしない）。
 * - HttpClient は長寿命インスタンスを注入すること。
 */
class VaultApiClient(private val client: HttpClient) {

    /** 残高+version 取得。 */
    suspend fun getBalance(uuid: UUID): Result<VaultBalanceResponse> =
        client.getJson("/api/Vault/${uuid}/balance")

    /** 取引ログ取得。 */
    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<MoneyLog>> =
        client.getJson("/api/Vault/${uuid}/logs") { paging(limit, offset) }

    /** 口座を冪等作成する（hasAccount/createPlayerAccount 用）。 */
    suspend fun ensureAccount(uuid: UUID): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/${uuid}/ensure")

    /** 入金（原子的差分）。成功時は確定残高+version。 */
    suspend fun deposit(body: VaultDepositRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/deposit", body)

    /** 出金。残高不足時は ApiHttpException(409, code=InsufficientFunds)。 */
    suspend fun withdraw(body: VaultWithdrawRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/withdraw", body)

    /** 送金（電子マネー→電子マネー）。成功時は送金元の確定残高+version。 */
    suspend fun transfer(body: VaultTransferRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/transfer", body)

    /** 管理用: 絶対値設定。成功時は確定残高+version。 */
    suspend fun set(body: VaultSetRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/set", body)

    /** 電子マネー ⇄ 銀行残高の移動。成功時は両残高。 */
    suspend fun move(body: VaultMoveRequest): Result<VaultMoveResponse> =
        client.postJson("/api/Vault/move", body)
}
