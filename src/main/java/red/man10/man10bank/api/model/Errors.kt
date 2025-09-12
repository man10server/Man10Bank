package red.man10.man10bank.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
)

@Serializable
data class ValidationProblemDetails(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val errors: Map<String, List<String>>? = null,
)

