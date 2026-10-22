package com.example.nutritrack.data

data class AiRequest(
    val prompt: String
)

data class AiResponse(
    val text: String,
    val modelName: String
)

sealed interface AiResult {
    data class Success(val response: AiResponse) : AiResult
    data class Failure(val error: AiError) : AiResult
}

sealed interface AiError {
    data object ServiceConfigurationPending : AiError
    data object EmptyResponse : AiError
    data object Timeout : AiError
    data object Network : AiError
    data object Sdk : AiError
    data object Cancelled : AiError
}

fun interface AiGateway {
    suspend fun generate(request: AiRequest): AiResult
}

object AiModelConfig {
    const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
    const val AGENT_MODEL_NAME = "gemini-3.5-flash-lite"
}
