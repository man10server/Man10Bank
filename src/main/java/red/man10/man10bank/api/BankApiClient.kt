package red.man10.man10bank.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import red.man10.man10bank.api.model.DepositRequest
import red.man10.man10bank.api.model.WithdrawRequest
import java.util.UUID

/**
 * /api/Bank 系のWebAPIクライアント。
 * - HttpClient は長寿命インスタンスを注入してください。
 */
class BankApiClient(private val client: HttpClient) {

    /**
     * 残高取得。
     * 仕様の明示スキーマが無いため、数値（Double）または {"balance": number} の両方を許容します。
     */
    suspend fun getBalance(uuid: UUID): Result<Double> = runCatching {
        // まずは数値として直接デコードを試みる
        try {
            return@runCatching client.get("/api/Bank/${uuid}/balance").body<Double>()
        } catch (_: Exception) {
            // テキスト→Double も試行
            val text = client.get("/api/Bank/${uuid}/balance").bodyAsText().trim()
            text.toDoubleOrNull()?.let { return@runCatching it }
            // 簡易パース: JSONを読み、balance を数値として抽出
            val elem = Json.parseToJsonElement(text)
            val num = elem.jsonObject["balance"]?.jsonPrimitive?.doubleOrNull
            requireNotNull(num) { "残高のレスポンス形式を解釈できません: $text" }
            num
        }
    }

    /**
     * 取引ログ取得。レスポンススキーマ未定義のため未加工JSON文字列を返します。
     */
    suspend fun getLogs(uuid: UUID, limit: Int = 100, offset: Int = 0): Result<String> = runCatching {
        client.get("/api/Bank/${uuid}/logs") {
            if (limit >= 0) parameter("limit", limit)
            if (offset >= 0) parameter("offset", offset)
        }.bodyAsText()
    }

    /** 入金（成功時は 2xx を前提に Unit 成功）。*/
    suspend fun deposit(body: DepositRequest): Result<Unit> = runCatching {
        client.post("/api/Bank/deposit") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Unit
    }

    /**
     * 出金。残高不足などで 409 Conflict を受け取った場合は
     * InsufficientBalanceException を失敗として返します。
     */
    suspend fun withdraw(body: WithdrawRequest): Result<Unit> {
        return try {
            client.post("/api/Bank/withdraw") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            Result.success(Unit)
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Conflict) {
                Result.failure(red.man10.man10bank.api.error.InsufficientBalanceException())
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
