package red.man10.man10bank.api.model.response

import kotlinx.serialization.Serializable

@Serializable
data class HealthPayload(
    val service: String,
    val serverTimeUtc: String,
    val startedAtUtc: String,
    val uptimeSeconds: Long,
    val database: Boolean,
)

