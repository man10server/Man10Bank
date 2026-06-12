package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.DepositRequest
import red.man10.man10bank.api.model.request.TransferRequest
import red.man10.man10bank.api.model.request.WithdrawRequest
import red.man10.man10bank.api.model.response.BalanceResponse
import red.man10.man10bank.api.model.response.MoneyLog
import java.util.UUID

/**
 * /api/Bank 系のWebAPIクライアント。
 * - HttpClient は長寿命インスタンスを注入してください。
 *
 * 残高系エンドポイント（balance/deposit/withdraw/transfer）はサーバーが
 * `{ "balance": number }`（BalanceResponse）を返す契約（DESIGN 1.4/1.5）。
 * 本クライアントは内部で BalanceResponse をデコードし、利用側の型を変えないよう
 * Result<Double> を返す。多段フォールバックや二重GETは行わない。
 */
class BankApiClient(private val client: HttpClient) {

    /** 残高取得。サーバーは `{ "balance": number }` を返す。 */
    suspend fun getBalance(uuid: UUID): Result<Double> =
        client.getJson<BalanceResponse>("/api/Bank/${uuid}/balance").map { it.balance }

    /** 取引ログ取得。MoneyLogの配列を返します。 */
    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<List<MoneyLog>> =
        client.getJson("/api/Bank/${uuid}/logs") { paging(limit, offset) }

    /** 入金。成功時は操作後の対象残高（Double）が返ります。 */
    suspend fun deposit(body: DepositRequest): Result<Double> =
        client.postJson<BalanceResponse>("/api/Bank/deposit", body).map { it.balance }

    /**
     * 出金。成功時は操作後の対象残高（Double）が返ります。
     * - 非2xx時のエラーメッセージは HttpClientFactory により ApiHttpException へ正規化されます。
     */
    suspend fun withdraw(body: WithdrawRequest): Result<Double> =
        client.postJson<BalanceResponse>("/api/Bank/withdraw", body).map { it.balance }

    /**
     * 送金（DESIGN 1.5）。
     * - サーバー側の単一トランザクションで送金元出金＋送金先入金＋MoneyLog2件を処理する。
     * - 成功時は送金元の新残高（Double）が返ります。残高不足時は ApiHttpException(409, code=InsufficientFunds)。
     */
    suspend fun transfer(body: TransferRequest): Result<Double> =
        client.postJson<BalanceResponse>("/api/Bank/transfer", body).map { it.balance }
}
