package red.man10.man10bank.api

import io.ktor.client.HttpClient
import red.man10.man10bank.api.model.request.ChequeCreateRequest
import red.man10.man10bank.api.model.request.ChequeUseRequest
import red.man10.man10bank.api.model.response.Cheque

/** /api/Cheques 用のAPIクライアント */
class ChequesApiClient(private val client: HttpClient) {

    suspend fun create(body: ChequeCreateRequest): Result<Cheque> =
        client.postJson("/api/Cheques", body)

    suspend fun get(id: Int): Result<Cheque> = client.getJson("/api/Cheques/${id}")

    suspend fun use(id: Int, body: ChequeUseRequest): Result<Cheque> =
        client.postJson("/api/Cheques/${id}/use", body)
}
