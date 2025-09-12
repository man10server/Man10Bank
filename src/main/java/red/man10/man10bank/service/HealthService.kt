package red.man10.man10bank.service

import red.man10.man10bank.api.model.response.HealthPayload

/** ヘルスチェック用サービスのインターフェイス */
interface HealthService {
    /** ヘルス情報を取得します。 */
    suspend fun getHealth(): Result<HealthPayload>
}

