package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.VaultDepositRequest
import red.man10.man10bank.api.model.request.VaultMoveRequest
import red.man10.man10bank.api.model.request.VaultSetRequest
import red.man10.man10bank.api.model.request.VaultTransferRequest
import red.man10.man10bank.api.model.request.VaultWithdrawRequest
import red.man10.man10bank.api.model.response.VaultBalanceResponse
import red.man10.man10bank.api.model.response.VaultConfigResponse
import red.man10.man10bank.api.model.response.VaultLog
import red.man10.man10bank.api.model.response.VaultMoveResponse
import red.man10.man10bank.api.model.response.VaultTransferResponse
import java.util.UUID

/**
 * /api/Vault 系の WebAPI クライアント。
 * - HttpClient は長寿命インスタンスを注入する。
 * - 全 suspend 関数。呼び出し側は Dispatchers.IO で実行すること。
 * - 非2xx は HttpClientFactory の HttpResponseValidator で ApiHttpException へ正規化され Result.failure になる。
 * - POST は HttpClientFactory のリトライ対象外(冪等キーは operationId でサービス側が担保する)。
 */
class VaultApiClient(private val client: HttpClient) {

    suspend fun getBalance(uuid: UUID): Result<VaultBalanceResponse> =
        client.getJson("/api/Vault/${uuid}/balance")

    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<VaultLog>> =
        client.getJson("/api/Vault/${uuid}/logs") { paging(limit, offset) }

    suspend fun getConfig(): Result<VaultConfigResponse> =
        client.getJson("/api/Vault/config")

    suspend fun deposit(body: VaultDepositRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/deposit", body)

    suspend fun withdraw(body: VaultWithdrawRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/withdraw", body)

    suspend fun transfer(body: VaultTransferRequest): Result<VaultTransferResponse> =
        client.postJson("/api/Vault/transfer", body)

    suspend fun move(body: VaultMoveRequest): Result<VaultMoveResponse> =
        client.postJson("/api/Vault/move", body)

    suspend fun set(body: VaultSetRequest): Result<VaultBalanceResponse> =
        client.postJson("/api/Vault/set", body)
}
