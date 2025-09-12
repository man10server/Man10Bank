package red.man10.man10bank.service.impl

import red.man10.man10bank.api.HealthApiClient
import red.man10.man10bank.api.model.response.HealthPayload
import red.man10.man10bank.service.HealthService

/** Health API を利用した実装 */
class HealthServiceApi(
    private val client: HealthApiClient
) : HealthService {
    override suspend fun getHealth(): Result<HealthPayload> = client.get()
}

